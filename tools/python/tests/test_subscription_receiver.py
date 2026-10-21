"""本地订阅接收端测试；仅使用临时目录和随机本机端口，不连接真实平台。"""
import base64
import hashlib
import hmac
import importlib
import io
import json
import struct
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stdout
from email.message import Message
from http.client import HTTPConnection
from pathlib import Path
from unittest.mock import MagicMock, patch

receiver = importlib.import_module("subscription_receiver")


def signed_headers(body, application="app-A", client="ac_A", secret="cs_A", event="event-1", timestamp=None):
    timestamp = str(int(time.time() * 1000) if timestamp is None else timestamp)
    parts = ["1", application, "", client, timestamp, event, hashlib.sha256(body).hexdigest()]
    data = b""
    for part in parts:
        encoded = part.encode("utf-8")
        data += struct.pack(">I", len(encoded)) + encoded
    mac = base64.urlsafe_b64encode(hmac.new(secret.encode("utf-8"), data, hashlib.sha256).digest()).rstrip(b"=").decode()
    return dict(zip(receiver.SIGNATURE_HEADERS, ["1", application, client, timestamp, event, mac]))


@contextmanager
def running(config):
    with receiver.ReceiverServer(("127.0.0.1", 0), config) as server:
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(timeout=5)


def request(server, body=b"{}", headers=None, method="POST", path="/webhook"):
    client = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
    try:
        client.request(method, path, body=body, headers=headers or {})
        response = client.getresponse()
        return response.status, json.loads(response.read())
    finally:
        client.close()


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.output = self.enterContext(redirect_stdout(io.StringIO()))
        self.a = receiver.ReceiverConfig("app-A", "ac_A", "cs_A", Path(self.temp) / "A.jsonl", "A")
        self.b = receiver.ReceiverConfig("app-B", "ac_B", "cs_B", Path(self.temp) / "B.jsonl", "B")
        self.body = '{ "id": "event-1", "subject": "device-1", "data": {"名称": "设备甲"} }'.encode()

    def rows(self, config):
        return [json.loads(line) for line in config.log_file.read_text(encoding="utf-8").splitlines()]

    def test_current_java_signature_vector_with_empty_platform_domain(self):
        # 使用当前 common-component 的 HttpSignatureUtils.canonical 和 JDK HmacSHA256 生成。
        config = receiver.ReceiverConfig("doc-app-1", "doc-webhook-key-1", "documentation-webhook-secret-0001", self.a.log_file)
        headers = Message()
        values = ["1", "doc-app-1", "doc-webhook-key-1", "1720000000000", "doc-event-1",
                  "3E4lweecnce0e-xwtskGPnyrq78VAxnvm_5-Ny1vlNs"]
        for name, value in zip(receiver.SIGNATURE_HEADERS, values):
            headers[name] = value
        trusted = receiver.verify_signature(headers, b'{"id":"doc-event-1","value":1}', config, 1720000000000)
        self.assertEqual("doc-app-1", trusted["X-Application-Id"])
        self.assertNotIn("X-Signature", trusted)

    def test_old_six_field_signature_is_rejected_without_fallback(self):
        config = receiver.ReceiverConfig("doc-app-1", "doc-webhook-key-1", "documentation-webhook-secret-0001", self.a.log_file)
        headers = Message()
        values = ["1", "doc-app-1", "doc-webhook-key-1", "1720000000000", "doc-event-1",
                  "90yr64hUXwmzfjJeT4siAm9ZUzuSGZiCmI-e_7wKyHo"]
        for name, value in zip(receiver.SIGNATURE_HEADERS, values):
            headers[name] = value
        with self.assertRaisesRegex(receiver.SignatureError, "invalid_signature"):
            receiver.verify_signature(headers, b'{"id":"doc-event-1","value":1}', config, 1720000000000)

    def test_new_java_utf8_vector_preserves_raw_body_and_byte_lengths(self):
        # 对齐公共组件现有跨语言向量，验证 UTF-8 字节长度而非字符长度。
        secret = "".join(chr(value) for value in range(32))
        config = receiver.ReceiverConfig("app-1", " key-雪 ", secret, self.a.log_file)
        headers = Message()
        values = ["1", "app-1", " key-雪 ", "1720000000123", "事件-🌡 ",
                  "XrgB5r4i2w8nIjYCu8-HHXTaB9444aHXqyWMuHV8rUA"]
        for name, value in zip(receiver.SIGNATURE_HEADERS, values):
            headers[name] = value
        body = '{"值":"设备🌡"}\n'.encode("utf-8")
        self.assertEqual("app-1", receiver.verify_signature(headers, body, config, 1720000000123)["X-Application-Id"])
        with self.assertRaisesRegex(receiver.SignatureError, "invalid_signature"):
            receiver.verify_signature(headers, body.rstrip(), config, 1720000000123)

    def test_api_channel_rejects_platform_headers_instead_of_ignoring_identity(self):
        for values in (["region-a"], [""], ["region-a", "region-b"]):
            with self.subTest(values=values):
                headers = Message()
                for name, value in signed_headers(self.body).items():
                    headers[name] = value
                for value in values:
                    headers["X-Platform-Id"] = value
                with self.assertRaisesRegex(receiver.SignatureError, "unexpected_platform_identity"):
                    receiver.verify_signature(headers, self.body, self.a)

    def test_two_receivers_accept_only_their_application_and_separate_logs(self):
        with running(self.a) as a, running(self.b) as b:
            headers_a = signed_headers(self.body)
            headers_b = signed_headers(self.body, "app-B", "ac_B", "cs_B")
            self.assertEqual(200, request(a, self.body, headers_a)[0])
            self.assertEqual(200, request(b, self.body, headers_b)[0])
            self.assertEqual(401, request(a, self.body, headers_b)[0])
            self.assertEqual(401, request(b, self.body, headers_a)[0])
        for config in (self.a, self.b):
            rows = self.rows(config)
            self.assertEqual([True, False], [row["verified"] for row in rows])
            self.assertEqual(config.application_id, rows[0]["applicationId"])
            self.assertEqual(self.body.decode(), rows[0]["rawBody"])
            self.assertEqual(json.loads(self.body), rows[0]["body"])
            self.assertNotIn("body", rows[1])
            self.assertNotIn("headers", rows[1])
            self.assertNotIn(config.secret, config.log_file.read_text(encoding="utf-8"))
        self.assertNotIn("cs_A", self.output.getvalue())
        self.assertNotIn("cs_B", self.output.getvalue())

    def test_rejects_missing_wrong_secret_tampered_body_identity_and_expired_signature(self):
        with running(self.a) as server:
            cases = [
                (self.body, {}),
                (self.body, signed_headers(self.body, secret="wrong-secret")),
                (self.body + b" ", signed_headers(self.body)),
                (self.body, {**signed_headers(self.body), "X-Application-Id": "app-B"}),
                (self.body, signed_headers(self.body, application="app-B")),
                (self.body, signed_headers(self.body, client="ac_B")),
                (self.body, signed_headers(self.body, timestamp=0)),
            ]
            for body, headers in cases:
                with self.subTest(headers=list(headers)):
                    self.assertEqual(401, request(server, body, headers)[0])
        self.assertTrue(all(not row["verified"] and "rawBody" not in row for row in self.rows(self.a)))

    def test_header_uniqueness_version_timestamp_and_time_boundary(self):
        clock = int(time.time() * 1000)
        for delta in (-300000, 300000):
            headers = Message()
            for key, value in signed_headers(self.body, timestamp=clock + delta).items():
                headers[key] = value
            self.assertEqual("app-A", receiver.verify_signature(headers, self.body, self.a, clock)["X-Application-Id"])
        headers = Message()
        for key, value in signed_headers(self.body, timestamp=clock).items():
            headers[key] = value
        headers["X-Application-Id"] = "app-A"
        with self.assertRaisesRegex(receiver.SignatureError, "duplicate"):
            receiver.verify_signature(headers, self.body, self.a, clock)
        with running(self.a) as server:
            for name, value in [("X-Signature-Version", "2"), ("X-Timestamp", "-1"),
                                ("X-Timestamp", "１"), ("X-Timestamp", "9" * 20)]:
                if value == "１":
                    values = Message()
                    for k, v in signed_headers(self.body).items():
                        values[k] = value if k == name else v
                    with self.assertRaisesRegex(receiver.SignatureError, "invalid_timestamp"):
                        receiver.verify_signature(values, self.body, self.a)
                else:
                    self.assertEqual(401, request(server, self.body, {**signed_headers(self.body), name: value})[0])

    def test_signed_invalid_json_is_not_accepted_and_generic_event_subject_is_allowed(self):
        with running(self.a) as server:
            for body in (b"not-json", b"\xff"):
                self.assertEqual(400, request(server, body, signed_headers(body))[0])
            self.assertEqual(200, request(server, self.body, signed_headers(self.body))[0])
        self.assertEqual("device-1", self.rows(self.a)[-1]["body"]["subject"])

    def test_event_id_must_match_signed_header(self):
        with running(self.a) as server:
            for body in (b'{}', b'[]', b'{"id":"other-event"}'):
                status, result = request(server, body, signed_headers(body))
                self.assertEqual(400, status)
                self.assertEqual("event_id_mismatch", result["reason"])
        self.assertTrue(all(not item["verified"] for item in self.rows(self.a)))

    def test_health_methods_and_log_do_not_store_sensitive_headers_or_query(self):
        with running(self.a) as server:
            status, health = request(server, method="GET", path="/health")
            self.assertEqual((200, True), (status, health["signatureRequired"]))
            self.assertEqual(404, request(server, method="GET", path="/other")[0])
            self.assertEqual(404, request(server, path="/other")[0])
            for method in ("PUT", "PATCH"):
                headers = {**signed_headers(self.body), "Authorization": "Bearer hidden-token", "Cookie": "hidden-cookie"}
                self.assertEqual(200, request(server, self.body, headers, method, "/webhook?secret=hidden-query")[0])
        contents = self.a.log_file.read_text(encoding="utf-8") + self.output.getvalue()
        for value in ("hidden-token", "hidden-cookie", "hidden-query", signed_headers(self.body)["X-Signature"]):
            self.assertNotIn(value, contents)

    def test_framing_and_storage_error_do_not_acknowledge_success(self):
        with running(self.a) as server:
            self.assertEqual(400, request(server, headers={"Content-Length": "-1"})[0])
            self.assertEqual(411, request(server, headers={"Transfer-Encoding": "chunked"})[0])
            # 用日志目录模拟无法写入的文件路径，不接触用户真实记录。
            blocked = receiver.ReceiverConfig("app-A", "ac_A", "cs_A", Path(self.temp), "A")
            server.config = blocked
            self.assertEqual(500, request(server, self.body, signed_headers(self.body))[0])

    def test_startup_prompts_hide_secret_and_bind_requested_port(self):
        fake = MagicMock()
        fake.__enter__.return_value = fake
        fake.server_port = 19091
        fake.serve_forever.side_effect = KeyboardInterrupt()
        with patch.object(receiver, "ReceiverServer", return_value=fake) as factory, \
             patch("builtins.input", side_effect=[" app-A ", " ac_A "]), \
             patch.object(receiver.getpass, "getpass", return_value=" cs_secret_with_spaces "), \
             patch("sys.argv", ["receiver", "--port", "19091", "--label", "A", "--log", str(self.a.log_file)]):
            receiver.main()
        address, config = factory.call_args.args
        self.assertEqual(("127.0.0.1", 19091), address)
        self.assertEqual(" cs_secret_with_spaces ", config.secret)
        self.assertNotIn(config.secret, repr(config))
        self.assertNotIn(config.secret, self.output.getvalue())

    def test_empty_credentials_and_non_hidden_input_are_rejected(self):
        with self.assertRaises(ValueError):
            receiver.ReceiverServer(("127.0.0.1", 0), receiver.ReceiverConfig("app-A", "ac_A", "", self.a.log_file))
        with patch("builtins.input", side_effect=["app-A", "ac_A"]), \
             patch.object(receiver.getpass, "getpass", side_effect=receiver.getpass.GetPassWarning()), \
             patch("sys.argv", ["receiver"]), self.assertRaises(SystemExit):
            receiver.main()


if __name__ == "__main__":
    unittest.main(verbosity=2)