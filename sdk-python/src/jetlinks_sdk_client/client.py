"""同步与异步 HTTP 门面共用协议和模型，不自动重试业务请求。"""

import asyncio
import base64
import hashlib
import secrets
import threading
import time
from dataclasses import dataclass

import httpx

from .asset_scope import configure_scope, scope_headers

from .models import (
    ApplicationState,
    AuthorizationContext,
    Member,
    Role,
    UserRole,
    ApiGrant,
    RoleAuthorization,
    RoleMenu,
    AssetAccesses,
    Subscription,
    SubscriptionValidation,
    SubscriptionPage,
    DeliveryTaskPage,
    DeliveryRecordPage,
    DeliveryPayload,
    EventProvider,
    DeliveryChannel,
    model_from_dict,
)
from .protocol import SdkError, decode, encode, local_token, segment, target


class CachedTokenProvider:
    """线程安全缓存；刷新失败短暂冷却，失效前的结果不能回写。"""

    def __init__(self, loader, skew=5):
        self.loader, self.skew = loader, skew
        self._condition = threading.Condition()
        self._cached = self._error = None
        self._loading, self._generation, self._retry_at = False, 0, 0
        self.refreshes = self.hits = 0

    def token(self):
        with self._condition:
            while self._loading:
                self._condition.wait()
            if self._cached and time.time() < self._cached[1] - self.skew:
                self.hits += 1
                return self._cached[0]
            if self._error and time.time() < self._retry_at:
                raise self._error
            self._loading = True
            generation = self._generation
            self.refreshes += 1
        try:
            value = self.loader()
            if not value[0] or value[1] <= time.time():
                raise SdkError("SERIALIZATION", "Invalid token response")
            with self._condition:
                if generation == self._generation:
                    self._cached, self._error = value, None
            return value[0]
        except Exception as error:
            with self._condition:
                if generation == self._generation:
                    self._error, self._retry_at = error, time.time() + 1
            raise
        finally:
            with self._condition:
                if generation == self._generation:
                    self._loading = False
                self._condition.notify_all()

    def invalidate(self):
        with self._condition:
            self._generation += 1
            self._loading = False
            self._cached = self._error = None
            self._condition.notify_all()


class AsyncCachedTokenProvider:
    """事件循环内并发刷新合并；shield 防止单个调用取消共享刷新。"""

    def __init__(self, loader, skew=5):
        self.loader, self.skew = loader, skew
        self._cached = self._task = None
        self._generation, self._retry_at = 0, 0
        self.refreshes = self.hits = 0

    async def token(self):
        if self._cached and time.time() < self._cached[1] - self.skew:
            self.hits += 1
            return self._cached[0]
        if self._task and (not self._retry_at or time.time() < self._retry_at):
            return await asyncio.shield(self._task)
        generation = self._generation
        self._retry_at = 0

        async def load():
            self.refreshes += 1
            try:
                value = await self.loader()
                if not value[0] or value[1] <= time.time():
                    raise SdkError("SERIALIZATION", "Invalid token response")
                if generation == self._generation:
                    self._cached, self._task = value, None
                return value[0]
            except Exception:
                if generation == self._generation:
                    self._retry_at = time.time() + 1
                raise

        self._task = asyncio.create_task(load())
        return await asyncio.shield(self._task)

    def invalidate(self):
        self._generation += 1
        self._cached = self._task = None
        self._retry_at = 0


class _Client:
    asynchronous = False

    def __init__(
        self,
        base_url,
        token="",
        timeout=30,
        max_bytes=1048576,
        max_concurrency=32,
        *,
        _source=None,
        _asset_scopes=None,
    ):
        self._closed, self._owned = False, None
        self._asset_scopes = _asset_scopes or {}
        self._root = _source._root if _source is not None else self
        if _source is not None:
            self.base_url = _source.base_url
            self.token_provider = _source.token_provider
            self.max_bytes = _source.max_bytes
            self._http, self._budget = _source._http, _source._budget
            return
        target(base_url, "/")
        if min(timeout, max_bytes, max_concurrency) < 1:
            raise SdkError("INVALID_ARGUMENT", "Invalid client limits")
        self.base_url, self.token_provider, self.max_bytes = base_url, token, max_bytes
        client = httpx.AsyncClient if self.asynchronous else httpx.Client
        self._http = client(
            timeout=timeout,
            follow_redirects=False,
            limits=httpx.Limits(max_connections=max_concurrency),
        )
        self._budget = (
            asyncio.Semaphore(max_concurrency)
            if self.asynchronous
            else threading.BoundedSemaphore(max_concurrency)
        )

    @classmethod
    def local(cls, base, key, secret, scopes=None):
        def load():
            now = time.time()
            return local_token(key, secret, scopes, int(now * 1000)), now + 120

        if cls.asynchronous:

            async def async_load():
                return load()

            provider = AsyncCachedTokenProvider(async_load)
        else:
            provider = CachedTokenProvider(load)
        return cls(base, provider)

    @classmethod
    def oauth2(cls, base, key, secret, scopes=None):
        oauth = (AsyncOAuth2 if cls.asynchronous else OAuth2)(base, key, secret)
        result = cls(base, oauth.machine_tokens(scopes))
        result._owned = oauth
        return result

    def _ensure_open(self):
        if self._closed or self._root._closed:
            raise SdkError("INVALID_STATE", "Client closed")

    def asset_scope(self, action):
        """返回共享认证/连接/并发预算的范围视图；关闭视图不关闭根客户端。"""
        self._ensure_open()
        return type(self)(
            self.base_url,
            _source=self,
            _asset_scopes=configure_scope(self._asset_scopes, action),
        )

    def _options(
        self,
        path,
        method,
        body,
        headers,
        query,
        token,
        asset_scope=None,
        accept="application/json",
    ):
        self._ensure_open()
        headers = scope_headers(self._asset_scopes, headers, asset_scope, accept)
        if token:
            headers = [(k, v) for k, v in headers if k.lower() != "authorization"]
            headers.append(("Authorization", "Bearer " + token))
        if body is not None and not any(
            k.lower() == "content-type" for k, _ in headers
        ):
            headers.append(("Content-Type", "application/json"))
        content = (
            None
            if body is None
            else body
            if isinstance(body, (bytes, str))
            else encode(body)
        )
        return (
            method,
            target(self.base_url, path, query),
            {"headers": headers, "content": content},
        )

    def _unwrap(self, status, raw, unwrap, model):
        value = decode(raw) if raw else None
        platform_status = (
            value.get("status", 200) if unwrap and isinstance(value, dict) else 200
        )
        if not 200 <= status < 300 or platform_status != 200:
            raise SdkError(
                "REMOTE_ERROR",
                "JetLinks request failed",
                platform_status if platform_status != 200 else status,
                value,
            )
        value = (
            value.get("result", value) if unwrap and isinstance(value, dict) else value
        )
        return model_from_dict(model, value) if model else value

    def authorization_context(self):
        return self.request("/authorize/context", model=AuthorizationContext)

    def business_application(self, application_id):
        return BusinessApplications(self, application_id)

    def subscriptions(self, application_id):
        return Subscriptions(self, application_id)


class JetLinksClient(_Client):
    """同步门面；流使用 contextlib.closing，提前结束时及时释放连接。"""

    def request(
        self,
        path,
        *,
        method="GET",
        body=None,
        headers=None,
        query=None,
        raw=False,
        model=None,
        asset_scope=None,
    ):
        self._ensure_open()
        token = (
            self.token_provider
            if isinstance(self.token_provider, str)
            else self.token_provider.token()
        )
        method, url, options = self._options(
            path, method, body, headers, query, token, asset_scope
        )
        if not self._budget.acquire(blocking=False):
            raise SdkError("LIMIT_EXCEEDED", "Client concurrency exceeded")
        try:
            with self._http.stream(method, url, **options) as response:
                chunks, size = [], 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > self.max_bytes:
                        raise SdkError("LIMIT_EXCEEDED", "Response exceeds limit")
                    chunks.append(chunk)
                return self._unwrap(
                    response.status_code, b"".join(chunks), not raw, model
                )
        except httpx.HTTPError as cause:
            raise SdkError("TRANSPORT", "Platform transport failed") from cause
        finally:
            self._budget.release()

    def stream(
        self,
        path,
        *,
        method="GET",
        body=None,
        headers=None,
        query=None,
        model=None,
        asset_scope=None,
    ):
        self._ensure_open()
        token = (
            self.token_provider
            if isinstance(self.token_provider, str)
            else self.token_provider.token()
        )
        method, url, options = self._options(
            path,
            method,
            body,
            headers,
            query,
            token,
            asset_scope,
            "application/x-ndjson",
        )
        if not self._budget.acquire(blocking=False):
            raise SdkError("LIMIT_EXCEEDED", "Client concurrency exceeded")
        try:
            with self._http.stream(method, url, **options) as response:
                if not response.is_success:
                    raise SdkError(
                        "REMOTE_ERROR", "Stream request failed", response.status_code
                    )
                pending = b""
                for chunk in response.iter_bytes():
                    pending += chunk
                    while b"\n" in pending:
                        line, pending = pending.split(b"\n", 1)
                        if len(line) > self.max_bytes:
                            raise SdkError(
                                "LIMIT_EXCEEDED", "NDJSON line exceeds limit"
                            )
                        if line.strip():
                            value = decode(line)
                            yield model_from_dict(model, value) if model else value
                    if len(pending) > self.max_bytes:
                        raise SdkError("LIMIT_EXCEEDED", "NDJSON line exceeds limit")
                if pending.strip():
                    value = decode(pending)
                    yield model_from_dict(model, value) if model else value
        except httpx.HTTPError as cause:
            raise SdkError("TRANSPORT", "Stream transport failed") from cause
        finally:
            self._budget.release()

    def pages(self, path, query=None, model=None):
        query = dict(query or {})
        index, size = query.get("pageIndex", 0), query.get("pageSize", 100)
        _page_size(size)
        while True:
            page = self.request(
                path,
                method="POST",
                body={**query, "pageIndex": index, "pageSize": size},
            )
            _check_page(page, size)
            for value in page["data"]:
                yield model_from_dict(model, value) if model else value
            if not page["data"] or (index + 1) * size >= page["total"]:
                return
            index += 1

    def close(self):
        if self._closed:
            return
        self._closed = True
        if self._root is not self:
            return
        self._http.close()
        if self._owned:
            self._owned.close()
        if not isinstance(self.token_provider, str):
            self.token_provider.invalidate()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class AsyncJetLinksClient(_Client):
    """非阻塞门面；流使用 contextlib.aclosing，取消通过 finally 关闭 HTTP 响应。"""

    asynchronous = True

    async def request(
        self,
        path,
        *,
        method="GET",
        body=None,
        headers=None,
        query=None,
        raw=False,
        model=None,
        asset_scope=None,
    ):
        self._ensure_open()
        token = (
            self.token_provider
            if isinstance(self.token_provider, str)
            else await self.token_provider.token()
        )
        method, url, options = self._options(
            path, method, body, headers, query, token, asset_scope
        )
        if self._budget.locked():
            raise SdkError("LIMIT_EXCEEDED", "Client concurrency exceeded")
        async with self._budget:
            try:
                async with self._http.stream(method, url, **options) as response:
                    chunks, size = [], 0
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > self.max_bytes:
                            raise SdkError("LIMIT_EXCEEDED", "Response exceeds limit")
                        chunks.append(chunk)
                    return self._unwrap(
                        response.status_code, b"".join(chunks), not raw, model
                    )
            except httpx.HTTPError as cause:
                raise SdkError("TRANSPORT", "Platform transport failed") from cause

    async def stream(
        self,
        path,
        *,
        method="GET",
        body=None,
        headers=None,
        query=None,
        model=None,
        asset_scope=None,
    ):
        self._ensure_open()
        token = (
            self.token_provider
            if isinstance(self.token_provider, str)
            else await self.token_provider.token()
        )
        method, url, options = self._options(
            path,
            method,
            body,
            headers,
            query,
            token,
            asset_scope,
            "application/x-ndjson",
        )
        if self._budget.locked():
            raise SdkError("LIMIT_EXCEEDED", "Client concurrency exceeded")
        async with self._budget:
            try:
                async with self._http.stream(method, url, **options) as response:
                    if not response.is_success:
                        raise SdkError(
                            "REMOTE_ERROR",
                            "Stream request failed",
                            response.status_code,
                        )
                    pending = b""
                    async for chunk in response.aiter_bytes():
                        pending += chunk
                        while b"\n" in pending:
                            line, pending = pending.split(b"\n", 1)
                            if len(line) > self.max_bytes:
                                raise SdkError(
                                    "LIMIT_EXCEEDED", "NDJSON line exceeds limit"
                                )
                            if line.strip():
                                value = decode(line)
                                yield model_from_dict(model, value) if model else value
                        if len(pending) > self.max_bytes:
                            raise SdkError(
                                "LIMIT_EXCEEDED", "NDJSON line exceeds limit"
                            )
                    if pending.strip():
                        value = decode(pending)
                        yield model_from_dict(model, value) if model else value
            except httpx.HTTPError as cause:
                raise SdkError("TRANSPORT", "Stream transport failed") from cause

    async def pages(self, path, query=None, model=None):
        query = dict(query or {})
        index, size = query.get("pageIndex", 0), query.get("pageSize", 100)
        _page_size(size)
        while True:
            page = await self.request(
                path,
                method="POST",
                body={**query, "pageIndex": index, "pageSize": size},
            )
            _check_page(page, size)
            for value in page["data"]:
                yield model_from_dict(model, value) if model else value
            if not page["data"] or (index + 1) * size >= page["total"]:
                return
            index += 1

    async def close(self):
        if self._closed:
            return
        self._closed = True
        if self._root is not self:
            return
        await self._http.aclose()
        if self._owned:
            await self._owned.close()
        if not isinstance(self.token_provider, str):
            self.token_provider.invalidate()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        await self.close()


def _page_size(size):
    if not 1 <= size <= 1000:
        raise SdkError("INVALID_ARGUMENT", "Invalid page size")


def _check_page(page, size):
    if (
        not isinstance(page, dict)
        or not isinstance(page.get("data"), list)
        or len(page["data"]) > size
    ):
        raise SdkError("SERIALIZATION", "Invalid page response")


@dataclass
class Authorization:
    url: str
    state: str
    verifier: str
    redirectUri: str


class OAuth2:
    client_type = JetLinksClient

    def __init__(self, base_url, key, secret, basic=True):
        self.base_url, self.key, self.secret, self.basic = base_url, key, secret, basic
        self.transport = self.client_type(base_url)

    def _token(self, form):
        from urllib.parse import urlencode

        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if self.basic:
            headers["Authorization"] = (
                "Basic "
                + base64.b64encode((self.key + ":" + self.secret).encode()).decode()
            )
        else:
            form.update(client_id=self.key, client_secret=self.secret)
        result = self.transport.request(
            "/oauth2/token",
            method="POST",
            body=urlencode(form),
            headers=headers,
            raw=True,
        )

        def validate(value):
            if not value.get("access_token") or value.get("expires_in", 0) <= 0:
                raise SdkError("SERIALIZATION", "Invalid OAuth2 response")
            return value

        if self.transport.asynchronous:

            async def finish():
                return validate(await result)

            return finish()
        return validate(result)

    def machine(self, scopes=None):
        return self._token(
            {
                "grant_type": "client_credentials",
                **({"scope": " ".join(scopes)} if scopes is not None else {}),
            }
        )

    def exchange(self, code, redirect_uri, verifier=None):
        return self._token(
            {
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
                **({"code_verifier": verifier} if verifier is not None else {}),
            }
        )

    def machine_tokens(self, scopes=None):
        if self.transport.asynchronous:

            async def load():
                value = await self.machine(scopes)
                return value["access_token"], time.time() + value["expires_in"]

            return AsyncCachedTokenProvider(load, 10)

        def load():
            value = self.machine(scopes)
            return value["access_token"], time.time() + value["expires_in"]

        return CachedTokenProvider(load, 10)

    def authorization(self, redirect_uri, scopes=None):
        state, verifier = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        challenge = (
            base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest())
            .rstrip(b"=")
            .decode()
        )
        url = target(
            self.base_url,
            "/oauth2/authorize",
            {
                "response_type": "code",
                "client_id": self.key,
                "redirect_uri": redirect_uri,
                "state": state,
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                **({"scope": " ".join(scopes)} if scopes is not None else {}),
            },
        )
        return Authorization(url, state, verifier, redirect_uri)

    def close(self):
        return self.transport.close()


class AsyncOAuth2(OAuth2):
    client_type = AsyncJetLinksClient


class BusinessApplications:
    """同一业务端点定义复用于同步/异步客户端，返回对应的迭代器或 awaitable。"""

    def __init__(self, client, application_id):
        self.client, self.id = client, segment(application_id)

    def members(self):
        return self.client.pages(
            f"/user/detail/business_application/{self.id}/_query", model=Member
        )

    def roles(self):
        from urllib.parse import unquote

        return self.client.pages(
            "/role/_query",
            {
                "terms": [
                    {
                        "column": "applicationId",
                        "termType": "eq",
                        "value": unquote(self.id),
                    }
                ]
            },
            Role,
        )

    def user_roles(self):
        return self.client.pages(
            f"/business-application/{self.id}/user-roles/_query", model=UserRole
        )

    def grants(self):
        return self.client.pages(
            f"/business-application/{self.id}/api-grants/_query", model=ApiGrant
        )

    def role_authorization(self, role):
        return self.client.pages(
            f"/business-application/{self.id}/roles/{segment(role)}/authorization/_query",
            model=RoleAuthorization,
        )

    def role_menus(self, role):
        return self.client.pages(
            f"/business-application/{self.id}/roles/{segment(role)}/menus/_query",
            model=RoleMenu,
        )

    def assets(self, role):
        return self.client.request(
            f"/role/{segment(role)}/asset-accesses", model=list[AssetAccesses]
        )

    def sync_credential(self):
        return self.client.request(
            f"/business-application/{self.id}/credential/_sync", method="POST"
        )


class Subscriptions:
    def __init__(self, client, application_id):
        self.client, self.base = (
            client,
            f"/application/{segment(application_id)}/subscriptions",
        )

    def _call(self, tail, method="GET", body=None, model=None):
        return self.client.request(
            self.base + tail, method=method, body=body, model=model
        )

    def query(self, query=None):
        return self._call("/_query", "POST", query or {}, SubscriptionPage)

    def get(self, identity):
        return self._call("/" + segment(identity), model=Subscription)

    def create(self, body):
        return self._call("", "POST", body)

    def update(self, identity, body):
        return self._call("/" + segment(identity), "PUT", body)

    def enable(self, identity, enable=True):
        return self._call(
            "/" + segment(identity) + ("/_enable" if enable else "/_disable"), "POST"
        )

    def delete(self, identity):
        return self._call("/" + segment(identity), "DELETE")

    def validate(self, body):
        return self._call("/_validate", "POST", body, SubscriptionValidation)

    def events(self):
        return self._call("/events", model=list[EventProvider])

    def channels(self):
        return self._call("/channels", model=list[DeliveryChannel])

    def tasks(self, identity, query=None):
        return self._call(
            "/" + segment(identity) + "/tasks/_query",
            "POST",
            query or {},
            DeliveryTaskPage,
        )

    def records(self, identity, query=None):
        return self._call(
            "/" + segment(identity) + "/records/_query",
            "POST",
            query or {},
            DeliveryRecordPage,
        )

    def payload(self, identity, task, attempt):
        return self._call(
            f"/{segment(identity)}/tasks/{segment(task)}/attempts/{attempt}/payload",
            model=DeliveryPayload,
        )


class ApplicationClients:
    """有界同步客户端复用；每次 get 必须传入业务持久层的当前应用快照。"""

    client_type = JetLinksClient

    def __init__(self, limit=128):
        self.limit, self.entries, self.closed = limit, {}, False
        self._lock = threading.RLock()

    def get(
        self, namespace, state: ApplicationState, mode="oauth2", scopes=None, bearer=""
    ):
        with self._lock:
            return self._get(namespace, state, mode, scopes, bearer)

    def _get(self, namespace, state, mode, scopes, bearer):
        if self.closed:
            raise SdkError("INVALID_STATE", "Client manager closed")
        if not state.active:
            self.invalidate(namespace)
            raise SdkError("INVALID_ARGUMENT", "Inactive application")
        key = (
            namespace,
            state.platformId,
            state.applicationId,
            mode,
            None if scopes is None else tuple(sorted(set(scopes))),
        )
        fingerprint = (
            state.createdAt,
            state.apiBaseUrl,
            state.accessKey,
            state.accessSecret,
            state.keyRevision,
            bearer,
        )
        existing = self.entries.get(key)
        if existing and existing[0] == fingerprint:
            return existing[1]
        if not existing and len(self.entries) >= self.limit:
            raise SdkError("LIMIT_EXCEEDED", "Client capacity reached")
        if mode == "local":
            client = self.client_type.local(
                state.apiBaseUrl, state.accessKey, state.accessSecret, scopes
            )
        elif mode == "oauth2":
            client = self.client_type.oauth2(
                state.apiBaseUrl, state.accessKey, state.accessSecret, scopes
            )
        elif mode == "bearer":
            client = self.client_type(state.apiBaseUrl, bearer)
        else:
            raise SdkError("INVALID_ARGUMENT", "Invalid auth mode")
        self.entries[key] = fingerprint, client
        if existing:
            existing[1].close()
        return client

    def invalidate(self, namespace):
        with self._lock:
            for key in list(self.entries):
                if key[0] == namespace:
                    self.entries.pop(key)[1].close()

    def close(self):
        with self._lock:
            self.closed = True
            for _, client in self.entries.values():
                client.close()
            self.entries.clear()


class AsyncApplicationClients:
    """异步连接管理器，替换/失效时等待旧连接关闭，不创建无主后台关闭任务。"""

    def __init__(self, limit=128):
        self.limit, self.entries, self.closed = limit, {}, False
        self._lock = asyncio.Lock()

    async def get(self, namespace, state, mode="oauth2", scopes=None, bearer=""):
        async with self._lock:
            if self.closed:
                raise SdkError("INVALID_STATE", "Client manager closed")
            if not state.active:
                await self._invalidate(namespace)
                raise SdkError("INVALID_ARGUMENT", "Inactive application")
            key = (
                namespace,
                state.platformId,
                state.applicationId,
                mode,
                None if scopes is None else tuple(sorted(set(scopes))),
            )
            fingerprint = (
                state.createdAt,
                state.apiBaseUrl,
                state.accessKey,
                state.accessSecret,
                state.keyRevision,
                bearer,
            )
            existing = self.entries.get(key)
            if existing and existing[0] == fingerprint:
                return existing[1]
            if not existing and len(self.entries) >= self.limit:
                raise SdkError("LIMIT_EXCEEDED", "Client capacity reached")
            if mode == "local":
                client = AsyncJetLinksClient.local(
                    state.apiBaseUrl, state.accessKey, state.accessSecret, scopes
                )
            elif mode == "oauth2":
                client = AsyncJetLinksClient.oauth2(
                    state.apiBaseUrl, state.accessKey, state.accessSecret, scopes
                )
            elif mode == "bearer":
                client = AsyncJetLinksClient(state.apiBaseUrl, bearer)
            else:
                raise SdkError("INVALID_ARGUMENT", "Invalid auth mode")
            self.entries[key] = fingerprint, client
            if existing:
                await existing[1].close()
            return client

    async def _invalidate(self, namespace):
        for key in list(self.entries):
            if key[0] == namespace:
                await self.entries.pop(key)[1].close()

    async def invalidate(self, namespace):
        async with self._lock:
            await self._invalidate(namespace)

    async def close(self):
        async with self._lock:
            self.closed = True
            for _, client in self.entries.values():
                await client.close()
            self.entries.clear()


class SnapshotSynchronizer:
    """异步快照合并刷新；失败保留旧快照且短暂冷却。"""

    def __init__(self, loader, limit):
        self.loader, self.limit, self.current = loader, limit, ()
        self._task, self._retry_at = None, 0

    async def refresh(self):
        if self._task and (not self._retry_at or time.time() < self._retry_at):
            return await asyncio.shield(self._task)
        self._retry_at = 0

        async def load():
            values = []
            try:
                async for value in self.loader():
                    values.append(value)
                    if len(values) > self.limit:
                        raise SdkError("LIMIT_EXCEEDED", "Snapshot exceeds limit")
                self.current, self._task = tuple(values), None
                return self.current
            except Exception:
                self._retry_at = time.time() + 1
                raise

        self._task = asyncio.create_task(load())
        return await asyncio.shield(self._task)
