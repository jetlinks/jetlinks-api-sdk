"""以模板 RSA 私钥取实例凭据，独立于通知开关。"""

import base64
import secrets
import time
from dataclasses import dataclass, field
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from .client import JetLinksClient, AsyncJetLinksClient
from .protocol import SdkError, decrypt, lp, segment

_VERSION = "1"
_USAGE = "credential.read"
_REQUEST_DOMAIN = "business-application-credential/request"
_MIN_RSA_KEY_BITS = 2048
_MAX_ID_LENGTH = 64
_MAX_PLATFORM_ID_LENGTH = 128
_NONCE_BYTES = 16
_SIGNATURE_PARAMETERS = padding.PSS(
    mgf=padding.MGF1(hashes.SHA256()), salt_length=hashes.SHA256.digest_size
)


@dataclass(frozen=True)
class ApplicationCredential:
    access_key: str = field(repr=False)
    access_secret: str = field(repr=False)
    key_id: str
    key_revision: int


class _BusinessApplicationCredentials:
    """共用签名与解密；同步和异步入口各自管理传输生命周期。"""

    client_type = JetLinksClient

    def __init__(self, base_url, key_id, private_key):
        try:
            self.key = serialization.load_pem_private_key(
                private_key.encode() if isinstance(private_key, str) else private_key,
                password=None,
            )
        except (TypeError, ValueError) as error:
            raise SdkError("CRYPTOGRAPHY", "Cannot parse RSA private key") from error
        if (
            not isinstance(key_id, str)
            or not key_id.strip()
            or not isinstance(self.key, rsa.RSAPrivateKey)
            or self.key.key_size < _MIN_RSA_KEY_BITS
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid RSA credential identity")
        self.key_id = key_id
        self.client = self.client_type(base_url)

    def _request(self, template_id, application_id, platform_id):
        if (
            any(
                not isinstance(v, str)
                or not v
                or v.strip() != v
                or len(v) > _MAX_ID_LENGTH
                for v in (template_id, application_id)
            )
            or not isinstance(platform_id, str)
            or len(platform_id) > _MAX_PLATFORM_ID_LENGTH
            or any(not "!" <= c <= "~" for c in platform_id)
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid credential request identity")
        nonce = secrets.token_urlsafe(_NONCE_BYTES)
        timestamp = int(time.time() * 1000)
        expected = dict(
            version=_VERSION,
            platformId=platform_id or template_id,
            templateId=template_id,
            applicationId=application_id,
            usage=_USAGE,
            nonce=nonce,
            keyId=self.key_id,
        )
        content = lp(
            [
                _REQUEST_DOMAIN,
                "POST",
                _VERSION,
                template_id,
                application_id,
                platform_id or template_id,
                _USAGE,
                nonce,
                str(timestamp),
            ]
        )
        signature = self.key.sign(
            content,
            _SIGNATURE_PARAMETERS,
            hashes.SHA256(),
        )
        body = dict(
            version=_VERSION,
            platformId=platform_id,
            usage=_USAGE,
            nonce=nonce,
            timestamp=timestamp,
            sign=base64.urlsafe_b64encode(signature).decode().rstrip("="),
        )
        path = f"/business-application-template/{segment(template_id)}/{segment(application_id)}/credential/_encrypted"
        return path, body, expected

    def _decode(self, value, expected):
        if (
            not isinstance(value, dict)
            or any(value.get(k) != v for k, v in expected.items())
            or not isinstance(value.get("accessKey"), str)
            or not value["accessKey"]
            or not isinstance(value.get("encryptedAccessSecret"), str)
            or type(value.get("keyRevision")) is not int
            or value["keyRevision"] < 1
        ):
            raise SdkError("AUTHENTICATION", "Invalid credential response identity")
        secret = decrypt(value["encryptedAccessSecret"], self.key)
        if not secret:
            raise SdkError("AUTHENTICATION", "Empty credential response")
        return ApplicationCredential(
            value["accessKey"], secret, self.key_id, value["keyRevision"]
        )


class BusinessApplicationCredentials(_BusinessApplicationCredentials):
    """拥有同步传输客户端；不重试，私钥只解析一次。"""

    def read(self, template_id, application_id, platform_id=""):
        path, body, expected = self._request(template_id, application_id, platform_id)
        return self._decode(
            self.client.request(path, method="POST", body=body), expected
        )

    def close(self):
        self.client.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class AsyncBusinessApplicationCredentials(_BusinessApplicationCredentials):
    """只提供异步传输与上下文管理，避免同步 close 遗漏等待。"""

    client_type = AsyncJetLinksClient

    async def read(self, template_id, application_id, platform_id=""):
        path, body, expected = self._request(template_id, application_id, platform_id)
        return self._decode(
            await self.client.request(path, method="POST", body=body), expected
        )

    async def close(self):
        await self.client.close()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        await self.close()
