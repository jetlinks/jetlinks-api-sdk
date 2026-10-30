import base64
import time
import httpx
import pytest
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from jetlinks_sdk_client import (
    BusinessApplicationCredentials,
    AsyncBusinessApplicationCredentials,
    SdkError,
    decode,
    encode,
    local_token,
)
from jetlinks_sdk_client.protocol import lp


def fixture(reader_type):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    reader = reader_type("https://platform.example/gateway", "rsa-1", pem)
    mismatch = [False]

    def respond(request):
        assert (
            request.url.path
            == "/gateway/business-application-template/template/app/credential/_encrypted"
        )
        assert request.method == "POST" and "authorization" not in request.headers
        body = decode(request.content)
        assert (
            body["platformId"] == ""
            and abs(body["timestamp"] - time.time() * 1000) < 60_000
        )
        assert len(base64.urlsafe_b64decode(body["nonce"] + "==")) == 16
        key.public_key().verify(
            base64.urlsafe_b64decode(body["sign"] + "=" * (-len(body["sign"]) % 4)),
            lp(
                [
                    "business-application-credential/request",
                    "POST",
                    "1",
                    "template",
                    "app",
                    "template",
                    "credential.read",
                    body["nonce"],
                    str(body["timestamp"]),
                ]
            ),
            padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
            hashes.SHA256(),
        )
        encrypted = key.public_key().encrypt(
            b"application-secret",
            padding.OAEP(
                mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None
            ),
        )
        result = dict(
            version="1",
            templateId="template",
            platformId="template",
            applicationId="app",
            usage="credential.read",
            nonce="other" if mismatch[0] else body["nonce"],
            keyId="rsa-1",
            keyRevision=2,
            accessKey="access-key",
            encryptedAccessSecret=base64.b64encode(encrypted).decode(),
        )
        return httpx.Response(200, content=encode(dict(status=200, result=result)))

    return reader, mismatch, httpx.MockTransport(respond)


def test_sync_bootstrap_signs_and_decrypts():
    reader, mismatch, transport = fixture(BusinessApplicationCredentials)
    reader.client._http.close()
    reader.client._http = httpx.Client(transport=transport)
    with reader:
        value = reader.read("template", "app")
        assert value.access_secret == "application-secret" and value.key_revision == 2
        assert "application-secret" not in repr(value)
        assert local_token(value.access_key, value.access_secret).startswith("at_")
        mismatch[0] = True
        with pytest.raises(SdkError) as error:
            reader.read("template", "app")
        assert error.value.code == "AUTHENTICATION"


async def test_async_bootstrap_signs_and_decrypts():
    reader, mismatch, transport = fixture(AsyncBusinessApplicationCredentials)
    await reader.client._http.aclose()
    reader.client._http = httpx.AsyncClient(transport=transport)
    async with reader:
        assert (
            await reader.read("template", "app")
        ).access_secret == "application-secret"
        mismatch[0] = True
        with pytest.raises(SdkError):
            await reader.read("template", "app")
