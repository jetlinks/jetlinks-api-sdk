"""以模板 RSA 私钥取实例凭据，独立于通知开关。"""

import base64
import secrets
import time
from dataclasses import dataclass, field
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from .client import JetLinksClient, AsyncJetLinksClient
from .protocol import SdkError, lp, segment


@dataclass(frozen=True)
class ApplicationCredential:
    access_key: str = field(repr=False)
    access_secret: str = field(repr=False)
    key_id: str
    key_revision: int


class BusinessApplicationCredentials:
    """拥有匿名传输客户端；不重试，私钥只解析一次。"""

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
            or self.key.key_size < 2048
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid RSA credential identity")
        self.key_id = key_id
        self.client = self.client_type(base_url)

    def _request(self, template_id, application_id, platform_id):
        if (
            any(
                not isinstance(v, str) or not v or v.strip() != v or len(v) > 64
                for v in (template_id, application_id)
            )
            or not isinstance(platform_id, str)
            or len(platform_id) > 128
            or any(not 33 <= ord(c) <= 126 for c in platform_id)
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid credential request identity")
        nonce, timestamp = secrets.token_urlsafe(16), int(time.time() * 1000)
        expected = dict(
            version="1",
            platformId=platform_id or template_id,
            templateId=template_id,
            applicationId=application_id,
            usage="credential.read",
            nonce=nonce,
            keyId=self.key_id,
        )
        content = lp(
            [
                "business-application-credential/request",
                "POST",
                "1",
                template_id,
                application_id,
                platform_id or template_id,
                "credential.read",
                nonce,
                str(timestamp),
            ]
        )
        signature = self.key.sign(
            content,
            padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
            hashes.SHA256(),
        )
        body = dict(
            version="1",
            platformId=platform_id,
            usage="credential.read",
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
        try:
            secret = self.key.decrypt(
                base64.b64decode(value["encryptedAccessSecret"], validate=True),
                padding.OAEP(
                    mgf=padding.MGF1(hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            ).decode()
        except Exception as error:
            raise SdkError("CRYPTOGRAPHY", "Cannot decrypt credential") from error
        if not secret:
            raise SdkError("AUTHENTICATION", "Empty credential response")
        return ApplicationCredential(
            value["accessKey"], secret, self.key_id, value["keyRevision"]
        )

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


class AsyncBusinessApplicationCredentials(BusinessApplicationCredentials):
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
