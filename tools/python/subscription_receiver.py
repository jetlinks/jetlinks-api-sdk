#!/usr/bin/env python3
"""本地 API 订阅 HTTP 接收器：验证含应用身份和空平台域的 v1 HMAC，每个进程绑定一个 API 应用。

仅用于联调；Secret 启动时隐藏输入，不写入参数或配置文件。日志包含实际业务
正文，请妥善保管。验签成功只证明报文来源/完整性，不代表平台数据权限正确。
"""

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import threading
import time
import warnings
from dataclasses import dataclass, field
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


SIGNATURE_HEADERS = (
    "X-Signature-Version", "X-Application-Id", "X-Key-Id",
    "X-Timestamp", "X-Event-Id", "X-Signature",
)


@dataclass(frozen=True)
class ReceiverConfig:
    application_id: str
    client_id: str
    secret: str = field(repr=False)
    log_file: Path
    label: str = "接收端"


class SignatureError(ValueError):
    pass


def verify_signature(headers, raw_body, config, now_ms=None):
    values = []
    for name in SIGNATURE_HEADERS:
        items = headers.get_all(name, [])
        if len(items) != 1 or not items[0]:
            raise SignatureError("missing_or_duplicate_signature_header")
        values.append(items[0])
    version, application_id, key_id, timestamp, event_id, signature = values
    if version != "1":
        raise SignatureError("unsupported_signature_version")
    if application_id != config.application_id or key_id != config.client_id:
        raise SignatureError("application_or_client_mismatch")
    # 此接收器绑定 API 应用；业务应用的非空平台身份不能混入 API 通道。
    if headers.get_all("X-Platform-Id", []):
        raise SignatureError("unexpected_platform_identity")
    if not timestamp.isascii() or not timestamp.isdecimal() or len(timestamp) > 19:
        raise SignatureError("invalid_timestamp")
    current = int(time.time() * 1000) if now_ms is None else now_ms
    if abs(current - int(timestamp)) > 300_000:
        raise SignatureError("signature_expired")
    # 对原始正文字节签名，不能先解析 JSON 再序列化；Secret 不做 Base64 解码。
    # 新协议固定七个字段：API 应用不发送 X-Platform-Id，但空平台域仍编码为四个零字节。
    fields = (version, application_id, "", key_id, timestamp, event_id, hashlib.sha256(raw_body).hexdigest())
    encoded = [value.encode("utf-8") for value in fields]
    canonical = b"".join(len(value).to_bytes(4, "big") + value for value in encoded)
    expected = base64.urlsafe_b64encode(
        hmac.digest(config.secret.encode("utf-8"), canonical, "sha256")
    ).rstrip(b"=")
    if not hmac.compare_digest(signature.encode("utf-8"), expected):
        raise SignatureError("invalid_signature")
    return dict(zip(SIGNATURE_HEADERS[:-1], values[:-1]))


class ReceiverServer(ThreadingHTTPServer):
    def __init__(self, address, config):
        if not config.application_id or not config.client_id or not config.secret:
            raise ValueError("应用 ID、Client ID、Client Secret 均不能为空")
        self.config = config
        self.write_lock = threading.Lock()
        config.log_file.parent.mkdir(parents=True, exist_ok=True)
        super().__init__(address, PushHandler)


class PushHandler(BaseHTTPRequestHandler):
    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlsplit(self.path).path in ("/", "/health"):
            self.respond(200, {"status": "ok", "receiver": self.server.config.label,
                               "signatureRequired": True})
        else:
            self.respond(404, {"message": "健康检查使用 /health，推送使用 /webhook"})

    def save_record(self, record):
        record = {
            "receivedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "receiver": self.server.config.label,
            "client": self.client_address[0],
            "method": self.command,
            "path": urlsplit(self.path).path,
            **record,
        }
        try:
            with self.server.write_lock:
                with self.server.config.log_file.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(record, ensure_ascii=False) + "\n")
                print(json.dumps(record, ensure_ascii=False, indent=2), flush=True)
        except OSError:
            self.respond(500, {"success": False, "message": "接收记录保存失败"})
            return False
        return True

    def reject(self, status, reason):
        # 未验签数据不作为权限证据，不保存其正文、签名或其他请求头。
        if self.save_record({"verified": False, "status": status, "reason": reason}):
            self.respond(status, {"success": False, "verified": False, "reason": reason})

    def receive_push(self):
        if urlsplit(self.path).path != "/webhook":
            self.respond(404, {"message": "推送使用 /webhook"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or len(lengths) != 1:
            self.reject(411, "content_length_required")
            return
        try:
            length = int(lengths[0])
            if length < 0:
                raise ValueError()
        except ValueError:
            self.reject(400, "invalid_content_length")
            return
        raw_body = self.rfile.read(length)
        if len(raw_body) != length:
            self.reject(400, "incomplete_body")
            return
        try:
            trusted = verify_signature(self.headers, raw_body, self.server.config)
        except SignatureError as error:
            self.reject(401, str(error))
            return
        try:
            text = raw_body.decode("utf-8")
            payload = json.loads(text)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.reject(400, "invalid_json")
            return
        if not isinstance(payload, dict) or payload.get("id") != trusted["X-Event-Id"]:
            self.reject(400, "event_id_mismatch")
            return
        # API 订阅正文可能是设备等业务事件，不把 subject/data 当作接收应用身份。
        record = {
            "verified": True, "status": 200,
            "applicationId": trusted["X-Application-Id"],
            "eventId": trusted["X-Event-Id"],
            "headers": trusted,
            "sha256": hashlib.sha256(raw_body).hexdigest(),
            "rawBody": text, "body": payload,
        }
        if self.save_record(record):
            self.respond(200, {"success": True, "verified": True,
                               "receiver": self.server.config.label, "eventId": record["eventId"]})

    do_POST = receive_push
    do_PUT = receive_push
    do_PATCH = receive_push

    def log_message(self, format, *args):
        # 不输出完整请求行，避免意外记录 query 中的敏感信息。
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认仅本机")
    parser.add_argument("--port", type=int, default=19090)
    parser.add_argument("--label", default="接收端")
    parser.add_argument("--log", type=Path, default=Path("data/subscription-receiver.jsonl"))
    args = parser.parse_args()
    print(f"配置 {args.label}：使用创建订阅的 API 应用凭据，不是用户 Token。", flush=True)
    application_id = input("应用 ID（平台应用记录 ID，不是 Client ID）：").strip()
    client_id = input("Client ID（ac_...）：").strip()
    try:
        # 禁止 getpass 在不支持隐藏输入时回退为明文回显。
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            secret = getpass.getpass("Client Secret（cs_...，输入不显示）：")
    except getpass.GetPassWarning:
        parser.error("请在交互终端中启动，当前终端无法隐藏 Secret 输入")
    if not application_id or not client_id or not secret:
        parser.error("应用 ID、Client ID、Client Secret 均不能为空")
    config = ReceiverConfig(application_id, client_id, secret, args.log.resolve(), args.label)
    try:
        with ReceiverServer((args.host, args.port), config) as server:
            print(f"回调地址：http://{args.host}:{server.server_port}/webhook", flush=True)
            print(f"健康检查：http://{args.host}:{server.server_port}/health", flush=True)
            print(f"验签应用：{application_id}；Client ID：{client_id}", flush=True)
            print(f"接收记录：{config.log_file}", flush=True)
            print("强制 API 应用 HMAC 验签（七字段，含空平台域）；日志包含业务正文，仅供联调。Ctrl+C 停止。", flush=True)
            server.serve_forever()
    except KeyboardInterrupt:
        print("\n接收服务已停止。", flush=True)
    except OSError as error:
        parser.exit(1, f"启动/运行失败，请检查端口及日志路径：{error}\n")


if __name__ == "__main__":
    main()