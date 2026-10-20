import asyncio
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from jetlinks_sdk_client import (
    AssetScope,
    JetLinksClient,
    AsyncJetLinksClient,
    CachedTokenProvider,
    AsyncCachedTokenProvider,
    SdkError,
)


@pytest.fixture
def platform():
    seen = []

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            scopes = self.headers.get_all("X-Asset-Scope", [])
            seen.append((self.path, scopes, self.headers.get("Authorization")))
            query = json.loads(
                self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}"
            )
            result = scopes
            if self.path == "/pages" or self.path.endswith("/_query"):
                result = {
                    "data": [{"id": str(query.get("pageIndex", 0)), "scopes": scopes}],
                    "total": 2 if self.path == "/pages" else 1,
                }
            if self.path.endswith("/events"):
                result = []
            body = (
                (json.dumps({"scopes": scopes}) + "\n").encode()
                if self.path == "/stream"
                else json.dumps({"status": 200, "result": result}).encode()
            )
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_POST = do_GET

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", seen
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_sync_actual_wire_scope_views_and_lifecycle(platform):
    base, seen = platform
    token = CachedTokenProvider(lambda: ("shared", time.time() + 60))
    with JetLinksClient(base, token) as root:
        retained = []
        a = root.asset_scope(
            lambda s: (retained.append(s), s.tenant("T").organization("A"))
        )
        retained[0].organization("mutated")
        b = a.asset_scope(lambda s: s.organization("B"))
        assert root.request("/echo") == []
        assert a.request("/echo") == ["tenant:T", "org:A"]
        assert a.request(
            "/echo",
            headers={"x-asset-scope": "org:raw"},
            asset_scope=lambda s: s.organization("old").scope("org", "final:Id"),
        ) == ["tenant:T", "org:final:Id"]
        assert b.request("/echo", headers={"X-Asset-Scope": "org:raw"}) == [
            "tenant:T",
            "org:raw",
        ]
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(
                pool.map(lambda i: (a if i % 2 else b).request("/echo"), range(20))
            )
        for i, result in enumerate(results):
            assert result == ["tenant:T", "org:A" if i % 2 else "org:B"]
        pages = list(a.pages("/pages", {"pageSize": 1}))
        assert len(pages) == 2
        assert all(row["scopes"] == ["tenant:T", "org:A"] for row in pages)
        assert list(b.stream("/stream")) == [{"scopes": ["tenant:T", "org:B"]}]
        list(a.business_application("app").members())
        a.subscriptions("app").events()
        assert all(row[1] == ["tenant:T", "org:A"] for row in seen[-2:])
        assert all(row[2] == "Bearer shared" for row in seen)
        assert token.refreshes == 1
        a.close()
        with pytest.raises(SdkError, match="Client closed"):
            a.request("/echo")
        assert b.request("/echo") == ["tenant:T", "org:B"]
    with pytest.raises(SdkError, match="Client closed"):
        b.request("/echo")
    assert token.refreshes == 1


async def test_async_actual_wire_scope_views_and_lifecycle(platform):
    base, seen = platform

    async def load():
        return "shared", time.time() + 60

    token = AsyncCachedTokenProvider(load)
    async with AsyncJetLinksClient(base, token) as root:
        a = root.asset_scope(lambda s: s.tenant("T").organization("A"))
        b = a.asset_scope(lambda s: s.organization("B"))
        assert await root.request("/echo") == []
        assert await a.request(
            "/echo",
            headers=[("X-Asset-Scope", "org:raw"), ("X-Asset-Scope", "org:last")],
            asset_scope=lambda s: s.organization("final"),
        ) == ["tenant:T", "org:final"]
        results = await asyncio.gather(
            *[(a if i % 2 else b).request("/echo") for i in range(12)]
        )
        for i, result in enumerate(results):
            assert result == ["tenant:T", "org:A" if i % 2 else "org:B"]
        assert len([row async for row in a.pages("/pages", {"pageSize": 1})]) == 2
        assert [
            row
            async for row in b.stream(
                "/stream", asset_scope=lambda s: s.organization("stream")
            )
        ] == [{"scopes": ["tenant:T", "org:stream"]}]
        _ = [row async for row in a.business_application("app").members()]
        await a.subscriptions("app").events()
        assert all(row[1] == ["tenant:T", "org:A"] for row in seen[-2:])
        await a.close()
        with pytest.raises(SdkError, match="Client closed"):
            await a.request("/echo")
        assert await b.request("/echo") == ["tenant:T", "org:B"]
        assert token.refreshes == 1
    with pytest.raises(SdkError, match="Client closed"):
        await b.request("/echo")
    assert token.refreshes == 1


def test_invalid_scopes_and_legacy_header(platform):
    for value in (
        "",
        " a",
        "a ",
        "a\n",
        "a\x7f",
        "\u00a0a",
        "a\ufeff",
        "x" * 257,
        None,
    ):
        with pytest.raises(SdkError) as error:
            AssetScope().organization(value)
        assert error.value.code == "INVALID_ARGUMENT"
    with pytest.raises(SdkError):
        AssetScope().scope("bad:type", "id")
    with pytest.raises(SdkError):
        AssetScope().scope("x" * 65, "id")
    builder = AssetScope()
    for i in range(16):
        builder.scope(f"d{i}", "id")
    builder.scope("d0", "replacement")
    with pytest.raises(SdkError):
        builder.scope("extra", "id")
    base, seen = platform
    with JetLinksClient(base) as client:
        with pytest.raises(SdkError):
            client.request("/echo", asset_scope=lambda s: s.tenant(""))
        with pytest.raises(SdkError):
            client.request("/echo", headers={"X-Asset-Scope": "missing-colon"})
        assert seen == []
        assert client.request("/echo", headers={"X-Asset-Scope": "org:raw"}) == [
            "org:raw"
        ]
