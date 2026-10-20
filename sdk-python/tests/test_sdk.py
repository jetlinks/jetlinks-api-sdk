import asyncio
import json
import pathlib
import time
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes, serialization

from jetlinks_sdk_client import (
    ApplicationState,
    ApplicationClients,
    AsyncCachedTokenProvider,
    CachedTokenProvider,
    AsyncJetLinksClient,
    NotificationVerifier,
    CommandService,
    StructuredEvent,
    TrustedKey,
    VerifiedNotification,
    SnapshotSynchronizer,
    SdkError,
    apply_lifecycle,
    decode,
    encode,
    local_token,
    sign,
)
from jetlinks_sdk_client.protocol import decrypt

VECTORS = json.loads(
    (
        pathlib.Path(__file__).resolve().parents[2] / "examples/contract/vectors.json"
    ).read_text()
)


def state():
    return ApplicationState(
        platformId="east",
        applicationId="app",
        templateId="template",
        apiBaseUrl="http://localhost/prefix",
        accessKey="key",
        accessSecret="secret",
        createdAt=1,
        keyRevision=1,
        updatedAt=1,
    )


def test_independent_protocol_vectors_and_decimal():
    signature = VECTORS["signature"]
    assert sign(signature["secret"], signature["fields"]) == signature["expected"]
    for v in VECTORS["tokens"]:
        assert (
            local_token(v["key"], v["secret"], v["scopes"], v["issuedAt"])
            == v["expected"]
        )
    value = decode('{"usage":' + VECTORS["decimal"] + "}")
    assert value["usage"] == Decimal(VECTORS["decimal"])
    assert decode(encode(value)) == value
    assert '"123456' not in encode(value)


def test_rsa_oaep_parameters():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    encrypted = key.public_key().encrypt(
        "轮换-secret".encode(),
        padding.OAEP(
            mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None
        ),
    )
    import base64

    private = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    assert decrypt(base64.b64encode(encrypted), private) == "轮换-secret"


async def test_async_token_coalescing_cancellation_and_invalidation():
    event, calls = asyncio.Event(), 0

    async def load():
        nonlocal calls
        calls += 1
        await event.wait()
        return "token", time.time() + 60

    cache = AsyncCachedTokenProvider(load)
    first = asyncio.create_task(cache.token())
    second = asyncio.create_task(cache.token())
    await asyncio.sleep(0)
    first.cancel()
    with pytest.raises(asyncio.CancelledError):
        await first
    event.set()
    assert await second == "token"
    assert calls == 1
    assert await cache.token() == "token"
    cache.invalidate()
    assert await cache.token() == "token"
    assert calls == 2


def test_sync_token_thread_coalescing_and_cooldown():
    import concurrent.futures
    import threading

    gate, calls = threading.Event(), 0

    def load():
        nonlocal calls
        calls += 1
        gate.wait(2)
        return "token", time.time() + 60

    cache = CachedTokenProvider(load)
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(cache.token) for _ in range(10)]
        gate.set()
        assert [f.result() for f in futures] == ["token"] * 10
    assert calls == 1


async def test_notification_duplicate_headers_and_durable_error():
    verifier = NotificationVerifier(lambda *_: None)
    with pytest.raises(SdkError, match="signature header"):
        await verifier.verify({"X-Signature-Version": ["1", "1"]}, b"{}")


def test_lifecycle_delete_and_new_instance():
    notice = VerifiedNotification(
        "east",
        "app",
        "e",
        TrustedKey("business", "secret", templateId="template"),
        StructuredEvent(
            "e", "application.deleted", {"application": {"createTime": 1}}, occurredAt=3
        ),
        b"",
        {},
    )
    deleted = apply_lifecycle(state(), notice, lambda _: "")
    assert deleted.accessSecret is None
    notice.event.type, notice.event.occurredAt = "application.enabled", 4
    assert not apply_lifecycle(deleted, notice, lambda _: "").enabled
    notice.event.type, notice.event.occurredAt = "application.created", 6
    notice.event.data["application"]["createTime"] = 5
    recreated = apply_lifecycle(deleted, notice, lambda _: "")
    assert (
        recreated.createdAt == 5
        and recreated.deletedAt == 0
        and recreated.keyRevision == 0
    )


def test_sync_application_cache_and_http_boundary():
    clients = ApplicationClients(2)
    current = state()
    original = clients.get("a", current, "local")
    assert clients.get("a", replace(current), "local") is original
    assert clients.get("a", current, "local", []) is not original
    with pytest.raises(SdkError):
        clients.get("b", current)
    rotated = clients.get("a", replace(current, keyRevision=2), "local")
    assert rotated is not original
    with pytest.raises(SdkError):
        original.request("/test")
    clients.close()


async def test_async_client_error_limit_and_typed_context():
    async def respond(request):
        assert request.url.path.startswith("/prefix/")
        return (
            httpx.Response(403, json={"message": "forbidden"})
            if request.url.path.endswith("denied")
            else httpx.Response(
                200, json={"status": 200, "result": {"application": {"id": "app"}}}
            )
        )

    async with AsyncJetLinksClient("http://localhost/prefix") as client:
        await client._http.aclose()
        client._http = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        assert (await client.authorization_context()).application["id"] == "app"
        with pytest.raises(SdkError) as error:
            await client.request("/denied")
        assert error.value.code == "REMOTE_ERROR" and error.value.status == 403
        client.max_bytes = 4
        with pytest.raises(SdkError) as error:
            await client.request("/ok")
        assert error.value.code == "LIMIT_EXCEEDED"


async def test_snapshot_concurrent_refresh():
    calls = 0

    async def load():
        nonlocal calls
        calls += 1
        yield "complete"

    sync = SnapshotSynchronizer(load, 1)
    await asyncio.gather(sync.refresh(), sync.refresh())
    assert calls == 1 and sync.current == ("complete",)


async def test_signed_command_response_identity_and_failure():
    service = CommandService(lambda *_: state(), 1)
    service.on_error = lambda _: None
    service.register("Echo", False, lambda _s, p: p)

    async def invoke(identity):
        stamp, request_id = str(int(time.time() * 1000)), "a" * 32
        target = "/api/commands/1/" + identity
        signature = sign(
            "secret",
            [
                "http-command",
                "1",
                "request",
                "app",
                "east",
                "key",
                stamp,
                request_id,
                "POST",
                target,
                "application/json",
                "application/json",
            ],
        )
        response = await service.handle(
            "POST",
            target,
            {
                "X-Signature-Version": "1",
                "X-Platform-Id": "east",
                "X-Application-Id": "app",
                "X-Key-Id": "key",
                "X-Timestamp": stamp,
                "X-Request-Id": request_id,
                "X-Signature": signature,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            b'{"applicationId":"spoof"}',
        )
        assert response.headers["X-Signature"] == sign(
            "secret",
            [
                "http-command",
                "1",
                "response",
                "app",
                "east",
                "key",
                response.headers["X-Timestamp"],
                request_id,
                signature,
                str(response.status),
                response.headers["Content-Type"],
            ],
        )
        raw = b"".join([item async for item in response.body])
        await response.close()
        return response.status, decode(raw)

    status, body = await invoke("Echo")
    assert status == 200 and body["applicationId"] == "app"
    status, _ = await invoke("Missing")
    assert status == 404 and service.in_flight == 0
