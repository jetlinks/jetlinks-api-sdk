"""字节级签名与精确 JSON；不依赖平台或 Web 框架。"""

import base64
import hashlib
import hmac
import struct
import time
from dataclasses import fields, is_dataclass
from urllib.parse import quote, urlencode, urlsplit, unquote

import simplejson
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


class SdkError(Exception):
    """稳定 code/status；远端正文仅保存在 response，不拼接到默认消息。"""

    def __init__(self, code, message, status=0, response=None):
        super().__init__(message)
        self.code, self.status, self.response = code, status, response


class Headers:
    VERSION = "X-Signature-Version"
    PLATFORM = "X-Platform-Id"
    APPLICATION = "X-Application-Id"
    KEY = "X-Key-Id"
    TIMESTAMP = "X-Timestamp"
    EVENT = "X-Event-Id"
    REQUEST = "X-Request-Id"
    SIGNATURE = "X-Signature"


def header_values(headers):
    result = {}
    for key, value in headers.items():
        result.setdefault(key.lower(), []).extend(
            value if isinstance(value, list) else [value]
        )
    return result


def header(headers, name, required=True):
    values = header_values(headers).get(name.lower())
    if values is None and not required:
        return ""
    if values is None or len(values) != 1 or not values[0]:
        raise SdkError("AUTHENTICATION", "Invalid signature header")
    return values[0]


def check_headers(headers, now=None):
    value = header(headers, Headers.TIMESTAMP)
    if (
        header(headers, Headers.VERSION) != "1"
        or not value.isascii()
        or not value.isdigit()
        or len(value) > 16
        or str(int(value)) != value
        or abs((now if now is not None else int(time.time() * 1000)) - int(value))
        > 300000
    ):
        raise SdkError("AUTHENTICATION", "Expired or unsupported signature")


def lp(values):
    return b"".join(
        struct.pack(">I", len(raw)) + raw for raw in (v.encode("utf-8") for v in values)
    )


def sign(secret, values):
    return (
        base64.urlsafe_b64encode(hmac.digest(secret.encode(), lp(values), "sha256"))
        .rstrip(b"=")
        .decode()
    )


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def matches(expected, actual):
    return hmac.compare_digest(expected.encode(), actual.encode())


def decode(raw):
    try:
        return simplejson.loads(raw, use_decimal=True)
    except (ValueError, UnicodeError) as cause:
        raise SdkError("SERIALIZATION", "Invalid JSON") from cause


def _prepare(value):
    if is_dataclass(value):
        return {
            **getattr(value, "extra", {}),
            **{
                f.name: _prepare(getattr(value, f.name))
                for f in fields(value)
                if f.name != "extra"
            },
        }
    if isinstance(value, dict):
        return {k: _prepare(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [_prepare(v) for v in value]
    return value


def encode(value):
    try:
        return simplejson.dumps(
            _prepare(value),
            use_decimal=True,
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (ValueError, TypeError) as cause:
        raise SdkError("SERIALIZATION", "Cannot encode JSON") from cause


def decrypt(ciphertext, private_key):
    try:
        key = serialization.load_pem_private_key(private_key.encode(), password=None)
        return key.decrypt(
            base64.b64decode(ciphertext, validate=True),
            padding.OAEP(
                mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None
            ),
        ).decode()
    except (ValueError, TypeError) as cause:
        raise SdkError("CRYPTOGRAPHY", "Cannot decrypt credential") from cause


def local_token(key, secret, scopes=None, issued_at=None):
    """null Scope 保留平台默认权限，空集合明确表示无权限；签名与 Java 二进制格式一致。"""
    issued_at = int(time.time() * 1000) if issued_at is None else issued_at
    if not key or not secret or issued_at < 0:
        raise SdkError("INVALID_ARGUMENT", "Invalid credential")

    def text(value):
        raw = value.encode()
        if len(raw) > 1024:
            raise SdkError("LIMIT_EXCEEDED", "Token field too long")
        return struct.pack(">H", len(raw)) + raw

    sorted_scopes = (
        sorted({value.strip() for value in scopes}) if scopes is not None else []
    )
    if "" in sorted_scopes or len(sorted_scopes) > 65535:
        raise SdkError("INVALID_ARGUMENT", "Invalid scopes")
    raw = (
        struct.pack("<BqBB", 11, issued_at, 1, 2)
        + text(key)
        + struct.pack(">BH", scopes is not None, len(sorted_scopes))
        + b"".join(map(text, sorted_scopes))
        + text("")
    )
    derived = hmac.digest(
        secret.encode(), b"application-access/self-signed/v1", "sha256"
    )
    number = int.from_bytes(raw + hmac.digest(derived, raw, "sha256")[:16], "big")
    alphabet, result = (
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "",
    )
    while number:
        number, digit = divmod(number, 62)
        result = alphabet[digit] + result
    if len(result) + 3 > 4096:
        raise SdkError("LIMIT_EXCEEDED", "Token exceeds limit")
    return "at_" + result


def segment(value):
    return quote(value, safe="")


def target(base, path, query=None):
    try:
        url = urlsplit(base)
        port = url.port
        if (
            url.scheme not in ("http", "https")
            or not url.hostname
            or url.username
            or url.password
            or url.query
            or url.fragment
            or (port is not None and port > 65535)
            or not path.startswith("/")
            or path.startswith("//")
            or any(c in path for c in "?#\\")
            or any(unquote(part) in (".", "..") for part in path.split("/"))
        ):
            raise ValueError()
    except ValueError as cause:
        raise SdkError(
            "INVALID_ARGUMENT", "Expected trusted base and relative API path"
        ) from cause
    return base.rstrip("/") + path + ("?" + urlencode(query) if query else "")
