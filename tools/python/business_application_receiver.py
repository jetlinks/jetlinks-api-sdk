#!/usr/bin/env python3
"""无页面的业务应用通知接收示例：HMAC 验签、RSA 解密和持久 inbox。

只保存收到的原始报文（凭据仍是 RSA 密文），不保存或输出解密后的 AccessSecret。
通知信任配置在每次验签时重新读取；RSA 私钥在启动时加载。仅用于受控联调。
"""
import argparse
import base64
import hashlib
import hmac
import json
import sqlite3
import time
from contextlib import closing
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

HEADERS = ("X-Signature-Version", "X-Application-Id", "X-Platform-Id", "X-Key-Id",
           "X-Timestamp", "X-Event-Id", "X-Signature")
CREDENTIAL_EVENTS = {"application.created", "application.credential.rotated", "application.credential.synced"}
MAX_BODY = 128 * 1024


class ReceiveError(ValueError):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


@dataclass(repr=False)
class Credential:
    access_key: str
    access_secret: str = field(repr=False)
    revision: int


class Receiver:
    """信任来自本地配置，不能从本次通知或未知应用中搜索一个可用密钥。"""
    def __init__(self, config_file):
        self.config_file = Path(config_file)
        config = self.configuration()
        self.receiver_id = config["key_id"]
        key_file = self.config_file.parent / config["private_key_file"]
        self.private_key = serialization.load_pem_private_key(key_file.read_bytes(), password=None)
        if not isinstance(self.private_key, rsa.RSAPrivateKey) or self.private_key.key_size < 2048:
            raise ValueError("RSA private key must have at least 2048 bits")

    def configuration(self):
        try:
            config = json.loads(self.config_file.read_text(encoding="utf-8"))
            if not isinstance(config, dict) or not isinstance(config.get("templates"), list):
                raise ValueError()
            for name in ("key_id", "private_key_file"):
                if not isinstance(config.get(name), str) or not config[name].strip():
                    raise ValueError()
            seen_keys, seen_templates = set(), set()
            for item in config["templates"]:
                if not isinstance(item, dict) or any(not isinstance(item.get(k), str) or not item[k].strip()
                        for k in ("platformId", "templateId", "keyId", "secret")):
                    raise ValueError()
                key = (item["platformId"], item["keyId"])
                template = (item["platformId"], item["templateId"])
                if key in seen_keys or template in seen_templates or len(item["secret"].encode("utf-8")) < 32:
                    raise ValueError()
                seen_keys.add(key)
                seen_templates.add(template)
            return config
        except (OSError, ValueError, TypeError):
            raise ReceiveError(503, "notification_configuration_unavailable") from None

    def verify(self, headers, raw_body, now_ms=None):
        values = []
        for name in HEADERS:
            items = headers.get_all(name, [])
            if len(items) != 1 or not items[0]:
                raise ReceiveError(401, "missing_or_duplicate_signature_header")
            values.append(items[0])
        version, application_id, platform_id, key_id, stamp, event_id, signature = values
        if version != "1" or not stamp.isascii() or not stamp.isdecimal() or len(stamp) > 19:
            raise ReceiveError(401, "invalid_signature_headers")
        current = int(time.time() * 1000) if now_ms is None else now_ms
        if abs(current - int(stamp)) > 300_000:
            raise ReceiveError(401, "signature_expired")
        template = next((item for item in self.configuration()["templates"]
                         if item["platformId"] == platform_id and item["keyId"] == key_id), None)
        if template is None:
            raise ReceiveError(401, "unknown_notification_key")
        # 长度按 UTF-8 字节计算；Secret 使用原文，不做 Base64 解码。
        fields = (version, application_id, platform_id, key_id, stamp, event_id,
                  hashlib.sha256(raw_body).hexdigest())
        encoded = [value.encode("utf-8") for value in fields]
        canonical = b"".join(len(value).to_bytes(4, "big") + value for value in encoded)
        expected = base64.urlsafe_b64encode(hmac.digest(template["secret"].encode("utf-8"), canonical, "sha256")).rstrip(b"=")
        if not hmac.compare_digest(signature.encode("utf-8"), expected):
            raise ReceiveError(401, "signature_mismatch")
        try:
            event = json.loads(raw_body.decode("utf-8"))
            app = event["data"]["application"]
            if event["id"] != event_id or not isinstance(event["type"], str):
                raise ValueError()
            if app["id"] != application_id or app["templateId"] != template["templateId"]:
                raise ReceiveError(403, "application_or_template_mismatch")
        except (ValueError, TypeError, KeyError) as error:
            if isinstance(error, ReceiveError):
                raise
            raise ReceiveError(400, "invalid_event") from None
        credential = self.decrypt(event) if event["type"] in CREDENTIAL_EVENTS else None
        return (platform_id, application_id, template["templateId"], event_id), event, credential

    def decrypt(self, event):
        """只解密凭据字段；返回值供接入业务安全保存，不写入响应、日志或 inbox。"""
        try:
            item = event["data"]["payload"]["credential"]
            if (item["keyId"] != self.receiver_id or not isinstance(item["accessKey"], str)
                    or not item["accessKey"] or type(item["keyRevision"]) is not int or item["keyRevision"] < 1):
                raise ValueError()
            encrypted = base64.b64decode(item["encryptedAccessSecret"], validate=True)
            secret = self.private_key.decrypt(encrypted, padding.OAEP(
                mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)).decode("utf-8")
            if not secret.strip():
                raise ValueError()
            return Credential(item["accessKey"], secret, item["keyRevision"])
        except (ValueError, TypeError, KeyError):
            raise ReceiveError(400, "invalid_application_credential") from None


class Inbox:
    """唯一键在数据库中去重，同时固定应用模板归属；事务提交后才能应答成功。"""
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS applications (
                    platform_id TEXT, application_id TEXT, template_id TEXT NOT NULL,
                    PRIMARY KEY(platform_id, application_id));
                CREATE TABLE IF NOT EXISTS inbox (
                    platform_id TEXT, application_id TEXT, event_id TEXT, raw_body BLOB NOT NULL,
                    PRIMARY KEY(platform_id, application_id, event_id));
            """)

    def accept(self, identity, raw_body):
        platform, app, template, event = identity
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("INSERT OR IGNORE INTO applications VALUES (?,?,?)", (platform, app, template))
            bound = db.execute("SELECT template_id FROM applications WHERE platform_id=? AND application_id=?",
                               (platform, app)).fetchone()[0]
            if bound != template:
                raise ReceiveError(403, "application_template_conflict")
            inserted = db.execute("INSERT OR IGNORE INTO inbox VALUES (?,?,?,?)", (platform, app, event, raw_body))
            return inserted.rowcount == 1


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if urlsplit(self.path).path == "/health":
            self.reply(200, {"status": "ok", "signatureRequired": True})
        else:
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/jetlinks/events":
            self.reply(404, {"error": "not_found"})
            return
        try:
            sizes = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(sizes) != 1:
                raise ReceiveError(411, "content_length_required")
            try:
                size = int(sizes[0])
            except ValueError:
                raise ReceiveError(400, "invalid_content_length") from None
            if size < 0 or size > MAX_BODY:
                raise ReceiveError(413, "body_size_invalid")
            body = self.rfile.read(size)
            if len(body) != size:
                raise ReceiveError(400, "incomplete_body")
            identity, event, credential = self.server.receiver.verify(self.headers, body)
            inserted = self.server.inbox.accept(identity, body)
            # 不把 AccessSecret 返回给发送者。实际业务须在自己的安全存储中保存并处理 revision。
            self.reply(200, {"accepted": True, "duplicate": not inserted,
                             "credentialReceived": credential is not None})
        except ReceiveError as error:
            self.reply(error.status, {"error": error.code})
        except (OSError, sqlite3.Error):
            self.reply(500, {"error": "inbox_unavailable"})

    def log_message(self, format, *args):
        pass  # 不记录请求 query、密钥或完整通知正文。


class Server(ThreadingHTTPServer):
    def __init__(self, address, receiver, inbox):
        self.receiver, self.inbox = receiver, inbox
        super().__init__(address, Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="仅保存在接收端的 RSA 和模板信任配置")
    parser.add_argument("--database", type=Path, default=Path("data/business-notifications.sqlite3"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()
    try:
        receiver, inbox = Receiver(args.config), Inbox(args.database)
        with Server((args.host, args.port), receiver, inbox) as server:
            print(f"通知：http://{args.host}:{server.server_port}/jetlinks/events；仅用于受控联调。", flush=True)
            print(f"inbox：{args.database}；不提供页面或用户登录接口。", flush=True)
            server.serve_forever()
    except KeyboardInterrupt:
        print("接收服务已停止。")
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error):
        parser.exit(1, "启动失败，请检查配置、私钥、端口和数据库路径；详细配置不会写入日志。\n")


if __name__ == "__main__":
    main()
