"""使用临时目录、合成密钥和本机随机端口，不连接真实平台。"""
import base64
import copy
import hashlib
import hmac
import io
import json
import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stdout, closing
from email.message import Message
from http.client import HTTPConnection
from pathlib import Path
from unittest.mock import MagicMock, patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa, ec
import business_application_receiver as receiver


SECRET = "documentation-notification-secret-0001"
APP_SECRET = "documentation-application-access-secret"


def sign(body, platform="platform-A", app="app-1", key="notify-1", secret=SECRET, event="event-1", stamp=None):
    stamp = str(int(time.time() * 1000) if stamp is None else stamp)
    parts = ["1", app, platform, key, stamp, event, hashlib.sha256(body).hexdigest()]
    canonical = b"".join(len(value.encode()).to_bytes(4, "big") + value.encode() for value in parts)
    signature = base64.urlsafe_b64encode(hmac.new(secret.encode(), canonical, hashlib.sha256).digest()).rstrip(b"=").decode()
    return dict(zip(receiver.HEADERS, ["1", app, platform, key, stamp, event, signature]))


def message(values):
    result = Message()
    for key, value in values.items():
        result[key] = value
    return result


@contextmanager
def running(verify, inbox):
    with receiver.Server(("127.0.0.1", 0), verify, inbox) as server:
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(timeout=5)


def request(server, body=b"", headers=None, method="POST", path="/jetlinks/events"):
    with closing(HTTPConnection("127.0.0.1", server.server_port, timeout=5)) as client:
        client.request(method, path, body=body, headers=headers or {})
        response = client.getresponse()
        return response.status, json.loads(response.read())


class BusinessReceiverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.pem = cls.private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption())

    def setUp(self):
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.console = self.enterContext(redirect_stdout(io.StringIO()))
        (self.directory / "receiver.pem").write_bytes(self.pem)
        self.path = self.directory / "keys.json"
        self.config = {"key_id": "rsa-1", "private_key_file": "receiver.pem", "templates": [
            {"platformId": "platform-A", "templateId": "template-A", "keyId": "notify-1", "secret": SECRET}]}
        self.write_config()
        self.verify = receiver.Receiver(self.path)
        self.inbox = receiver.Inbox(self.directory / "inbox.sqlite3")
        self.event = {"id": "event-1", "type": "application.updated", "occurredAt": 1720000000000,
                      "data": {"application": {"id": "app-1", "templateId": "template-A", "name": "中文应用"}}}

    def write_config(self):
        self.path.write_text(json.dumps(self.config), encoding="utf-8")

    def body(self, event=None):
        return json.dumps(self.event if event is None else event, ensure_ascii=False, indent=2).encode()

    def credential_event(self, kind="application.created"):
        event = copy.deepcopy(self.event)
        event["type"] = kind
        ciphertext = self.private.public_key().encrypt(APP_SECRET.encode(), padding.OAEP(
            mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        event["data"]["payload"] = {"credential": {"accessKey": "example-access-key",
            "keyId": "rsa-1", "keyRevision": 1, "encryptedAccessSecret": base64.b64encode(ciphertext).decode()}}
        return event

    def rows(self):
        with closing(sqlite3.connect(self.inbox.path)) as db:
            return db.execute("SELECT platform_id,application_id,event_id,raw_body FROM inbox").fetchall()

    def test_raw_utf8_signature_and_rsa_credential_events(self):
        for kind in receiver.CREDENTIAL_EVENTS:
            body = self.body(self.credential_event(kind))
            identity, event, credential = self.verify.verify(message(sign(body)), body)
            self.assertEqual(("platform-A", "app-1", "template-A", "event-1"), identity)
            self.assertEqual(APP_SECRET, credential.access_secret)
            self.assertEqual("example-access-key", credential.access_key)
            self.assertEqual(1, credential.revision)
            self.assertNotIn(APP_SECRET, repr(credential))
            self.assertEqual(kind, event["type"])

    def test_signed_original_bytes_cannot_be_reserialized(self):
        body = self.body()
        compact = json.dumps(self.event, separators=(",", ":")).encode()
        with self.assertRaisesRegex(receiver.ReceiveError, "signature_mismatch"):
            self.verify.verify(message(sign(body)), compact)

    def test_identity_key_and_timestamp_rejections(self):
        body = self.body()
        values = sign(body)
        for key, value in (("X-Signature-Version", "2"), ("X-Timestamp", "-1"),
                           ("X-Timestamp", "１"), ("X-Timestamp", "9" * 20),
                           ("X-Platform-Id", "platform-B"), ("X-Key-Id", "unknown"),
                           ("X-Signature", "invalid"), ("X-Application-Id", "other")):
            with self.subTest(key=key, value=value), self.assertRaises(receiver.ReceiveError):
                self.verify.verify(message({**values, key: value}), body)
        for name in receiver.HEADERS:
            headers = message(values)
            headers[name] = values[name]
            with self.assertRaisesRegex(receiver.ReceiveError, "duplicate"):
                self.verify.verify(headers, body)
        with self.assertRaisesRegex(receiver.ReceiveError, "missing"):
            self.verify.verify(Message(), body)

    def test_timestamp_window_boundaries(self):
        now = 1720000000000
        body = self.body()
        for delta in (-300000, 300000):
            self.verify.verify(message(sign(body, stamp=now+delta)), body, now)
        with self.assertRaisesRegex(receiver.ReceiveError, "expired"):
            self.verify.verify(message(sign(body, stamp=now-300001)), body, now)

    def test_wrong_platform_or_api_canonical_domain_cannot_sign_business_event(self):
        body = self.body()
        headers = sign(body, platform="")
        headers["X-Platform-Id"] = "platform-A"
        with self.assertRaisesRegex(receiver.ReceiveError, "signature_mismatch"):
            self.verify.verify(message(headers), body)

    def test_event_and_application_must_match_verified_headers_and_template(self):
        for update in ({"id": "other"}, {"type": 1}, {"data": None},
                       {"data": {"application": {"id": "other", "templateId": "template-A"}}},
                       {"data": {"application": {"id": "app-1", "templateId": "other"}}}):
            body = self.body({**self.event, **update})
            with self.subTest(update=update), self.assertRaises(receiver.ReceiveError):
                self.verify.verify(message(sign(body)), body)
        for body in (b"not-json", b"[]", b"null", b"\xff"):
            with self.assertRaisesRegex(receiver.ReceiveError, "invalid_event"):
                self.verify.verify(message(sign(body)), body)

    def test_configuration_rotation_immediate_and_restart_restores_same_trust(self):
        body = self.body()
        self.verify.verify(message(sign(body)), body)
        self.config["templates"][0].update(keyId="notify-2", secret=SECRET + "-rotated")
        self.write_config()
        with self.assertRaisesRegex(receiver.ReceiveError, "unknown_notification_key"):
            self.verify.verify(message(sign(body)), body)
        headers = message(sign(body, key="notify-2", secret=SECRET + "-rotated"))
        before = self.verify.verify(headers, body)
        self.assertEqual(before, receiver.Receiver(self.path).verify(headers, body))

    def test_invalid_configuration_is_not_silently_ignored(self):
        good = copy.deepcopy(self.config)
        for config in ([], {}, {**good, "templates": None}, {**good, "key_id": ""},
                       {**good, "templates": [{}]}, {**good, "templates": good["templates"] * 2},
                       {**good, "templates": [{**good["templates"][0], "secret": "short"}]}):
            self.path.write_text(json.dumps(config), encoding="utf-8")
            with self.assertRaisesRegex(receiver.ReceiveError, "configuration_unavailable"):
                self.verify.configuration()
        self.path.unlink()
        with self.assertRaisesRegex(receiver.ReceiveError, "configuration_unavailable"):
            self.verify.configuration()

    def test_wrong_rsa_key_type_rejected(self):
        key = ec.generate_private_key(ec.SECP256R1())
        (self.directory / "receiver.pem").write_bytes(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with self.assertRaisesRegex(ValueError, "RSA private key"):
            receiver.Receiver(self.path)

    def test_decryption_checks_receiver_revision_and_ciphertext(self):
        for update in ({"keyId": "wrong"}, {"keyRevision": 0}, {"keyRevision": True},
                       {"accessKey": ""}, {"encryptedAccessSecret": "invalid-base64!"},
                       {"encryptedAccessSecret": base64.b64encode(b"not-rsa").decode()}):
            event = self.credential_event()
            event["data"]["payload"]["credential"].update(update)
            with self.subTest(update=update), self.assertRaisesRegex(receiver.ReceiveError, "invalid_application_credential"):
                self.verify.decrypt(event)
        with self.assertRaisesRegex(receiver.ReceiveError, "invalid_application_credential"):
            self.verify.decrypt(self.event)

    def test_inbox_durable_deduplication_and_platform_isolation(self):
        body = self.body()
        identity, _, _ = self.verify.verify(message(sign(body)), body)
        self.assertTrue(self.inbox.accept(identity, body))
        reloaded = receiver.Inbox(self.inbox.path)
        self.assertFalse(reloaded.accept(identity, body))
        self.assertTrue(reloaded.accept(("platform-B", "app-1", "template-B", "event-1"), body))
        self.assertEqual(2, len(self.rows()))
        self.assertEqual(body, self.rows()[0][3])

    def test_registered_application_cannot_move_to_another_template(self):
        identity = ("platform-A", "app-1", "template-A", "event-1")
        self.inbox.accept(identity, self.body())
        with self.assertRaisesRegex(receiver.ReceiveError, "application_template_conflict"):
            self.inbox.accept(("platform-A", "app-1", "template-B", "event-2"), self.body())
        self.assertEqual(1, len(self.rows()))

    def test_http_receipt_decryption_deduplication_and_no_plaintext_secret(self):
        body = self.body(self.credential_event())
        with running(self.verify, self.inbox) as server:
            status, result = request(server, body, sign(body))
            self.assertEqual(200, status)
            self.assertEqual({"accepted": True, "duplicate": False, "credentialReceived": True}, result)
            self.assertTrue(request(server, body, sign(body))[1]["duplicate"])
            self.assertEqual(200, request(server, method="GET", path="/health")[0])
            self.assertEqual(404, request(server, method="GET", path="/")[0])
            self.assertEqual(404, request(server, method="POST", path="/other")[0])
        self.assertEqual(1, len(self.rows()))
        self.assertNotIn(APP_SECRET, json.dumps(result) + self.console.getvalue())
        self.assertNotIn(APP_SECRET.encode(), self.inbox.path.read_bytes())
        self.assertNotIn(SECRET.encode(), self.inbox.path.read_bytes())

    def test_http_rejects_unsafe_framing_signatures_and_failed_storage(self):
        body = self.body()
        with running(self.verify, self.inbox) as server:
            self.assertEqual(411, request(server, headers={"Transfer-Encoding": "chunked"})[0])
            self.assertEqual(400, request(server, headers={"Content-Length": "abc"})[0])
            self.assertEqual(413, request(server, headers={"Content-Length": "-1"})[0])
            self.assertEqual(413, request(server, headers={"Content-Length": str(receiver.MAX_BODY+1)})[0])
            self.assertEqual(401, request(server, body)[0])
            with patch.object(self.inbox, "accept", side_effect=sqlite3.OperationalError("fixture")):
                self.assertEqual(500, request(server, body, sign(body))[0])
            self.path.unlink()
            self.assertEqual(503, request(server, body, sign(body))[0])
        self.assertEqual([], self.rows())

    def test_main_default_loopback_and_graceful_shutdown(self):
        server = MagicMock()
        server.__enter__.return_value = server
        server.server_port = 18080
        server.serve_forever.side_effect = KeyboardInterrupt()
        with patch.object(receiver, "Server", return_value=server) as factory, patch("sys.argv", [
            "receiver", "--config", str(self.path), "--database", str(self.inbox.path)]):
            receiver.main()
        self.assertEqual(("127.0.0.1", 18080), factory.call_args.args[0])
        self.assertNotIn(SECRET, self.console.getvalue())

    def test_main_start_failure_does_not_print_configuration(self):
        with patch("sys.argv", ["receiver", "--config", str(self.directory/"missing.json")]), \
             patch("sys.stderr", new_callable=io.StringIO) as errors, self.assertRaises(SystemExit):
            receiver.main()
        self.assertNotIn(SECRET, errors.getvalue())


if __name__ == "__main__":
    unittest.main()
