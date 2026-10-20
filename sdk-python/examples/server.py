"""共享前端的 Python 真实服务；业务流程均调用本语言 SDK。"""

import asyncio
import contextlib
import inspect
import pathlib
import sys
import time
import uuid
from dataclasses import replace
from urllib.parse import urlsplit, parse_qsl

import uvicorn
from starlette.applications import Starlette
from starlette.responses import Response, StreamingResponse
from starlette.routing import Route, Mount
from starlette.staticfiles import StaticFiles

from jetlinks_sdk_client import (
    ApplicationState,
    ApplicationClients,
    AsyncApplicationClients,
    AsyncJetLinksClient,
    AsyncOAuth2,
    CommandIds,
    CommandService,
    NotificationVerifier,
    NotificationReceiver,
    SdkError,
    SnapshotSynchronizer,
    decode,
    encode,
)
from jetlinks_sdk_client.protocol import segment, sha256
from store import ExampleStore, context_id, redact


def create_app(config):
    def private_key(key):
        trust = next(
            (t for t in config["templates"] if t["receiverKeyId"] == key), None
        )
        if not trust:
            raise SdkError("INVALID_ARGUMENT", "Unknown receiver key")
        return pathlib.Path(trust["privateKeyFile"]).read_text()

    store = ExampleStore(config["database"], private_key)
    clients, sync_clients = AsyncApplicationClients(), ApplicationClients()
    snapshots, records, pending, sessions = {}, [], {}, {}
    api_states = {
        a["contextId"]: ApplicationState(
            applicationId=a["applicationId"],
            apiBaseUrl=a["apiBaseUrl"],
            accessKey=a["accessKey"],
            accessSecret=a["accessSecret"],
            keyRevision=1,
        )
        for a in config["apiApplications"]
    }

    def id_of(state):
        return next(
            (key for key, value in api_states.items() if value is state), None
        ) or context_id(state)

    async def states():
        return [*api_states.values(), *await asyncio.to_thread(store.applications)]

    async def find(identity):
        state = next((s for s in await states() if id_of(s) == identity), None)
        if state is None:
            raise SdkError("INVALID_ARGUMENT", "Unknown application")
        return state

    def configured_token(state, mode):
        source = next(
            (a for a in config["apiApplications"] if a["contextId"] == id_of(state)),
            None,
        )
        source = source or next(
            (
                t
                for t in config["templates"]
                if t["platformId"] == state.platformId
                and t["templateId"] == state.templateId
            ),
            {},
        )
        return source.get(
            "managementToken" if mode == "management" else "userToken", ""
        )

    async def client_for(state, mode="oauth2"):
        return await clients.get(
            id_of(state) + (":" + mode if mode in ("management", "user") else ""),
            state,
            "bearer" if mode in ("management", "user") else mode,
            bearer=configured_token(state, mode),
        )

    def public_state(state):
        return {
            "contextId": id_of(state),
            "type": "business" if state.templateId else "api",
            "platformId": state.platformId,
            "applicationId": state.applicationId,
            "apiBaseUrl": state.apiBaseUrl,
            "keyRevision": state.keyRevision,
            "enabled": state.enabled,
            "deleted": bool(state.deletedAt),
            "credentialReady": state.active,
            "notificationUrl": config["publicBaseUrl"].rstrip("/")
            + "/jetlinks/events"
            + ("" if state.templateId else "/" + id_of(state)),
        }

    async def resolve(platform, app):
        return await asyncio.to_thread(store.find, platform, app)

    commands = CommandService(resolve)

    def register(identity, stream, handler):
        def record(state, parameters):
            entry = {
                "commandId": identity,
                "platformId": state.platformId,
                "applicationId": state.applicationId,
                "receivedAt": int(time.time() * 1000),
                "completion": "running",
                "result": [] if stream else None,
            }
            records.insert(0, entry)
            del records[100:]

            def display(value):
                return (
                    redact(value)
                    if len(encode(value).encode()) <= 65536
                    else {"truncated": True}
                )

            async def output():
                try:
                    async for value in handler(state, parameters):
                        if len(entry["result"]) < 100:
                            entry["result"].append(display(value))
                        yield value
                    entry["completion"] = "complete"
                except Exception:
                    entry["completion"] = "failed"
                    raise
                finally:
                    if entry["completion"] == "running":
                        entry["completion"] = "cancelled"

            async def execute():
                try:
                    value = handler(state, parameters)
                    if inspect.isawaitable(value):
                        value = await value
                    entry.update(result=display(value), completion="complete")
                    return value
                except Exception:
                    entry["completion"] = "failed"
                    raise

            return output() if stream else execute()

        commands.register(identity, stream, record)

    async def health(*_):
        return await asyncio.to_thread(store.health)

    async def resources(*_):
        yield {
            "id": "example",
            "name": "示例资源",
            "metrics": [
                {
                    "id": "requests",
                    "name": "示例资源使用次数",
                    "unit": "count",
                    "type": "request",
                    "limitType": "quotaTimeLimit",
                }
            ],
            "capabilities": [{"id": "use", "name": "使用资源"}],
        }

    async def usage(state, parameters):
        for item in await asyncio.to_thread(store.usage, state, parameters):
            yield item

    async def watch(_state, parameters):
        for index in range(min(100, max(1, parameters.get("limit", 1)))):
            await asyncio.sleep(0.2)
            yield {"index": index, "timestamp": int(time.time() * 1000)}

    register(CommandIds.GET_SERVICE_HEALTH, False, health)
    register(
        CommandIds.GET_SERVICE_INFO,
        False,
        lambda *_: {"name": "JetLinks Python Example", "version": "2.0.0a0"},
    )
    register(CommandIds.GET_TENANT_RESOURCES, True, resources)
    register(CommandIds.READ_TENANT_USAGE, True, usage)
    register(
        CommandIds.SAVE_TENANT_LIMITS,
        False,
        lambda s, p: asyncio.to_thread(store.save_limits, s, p),
    )
    register(
        "UseExampleResource", False, lambda s, p: asyncio.to_thread(store.use, s, p)
    )
    register("Echo", False, lambda s, p: p)
    register("WatchUsage", True, watch)

    async def collect(source):
        result = []
        async for item in source:
            result.append(item)
            if len(result) > 10000:
                raise SdkError("LIMIT_EXCEEDED", "Example snapshot exceeds limit")
        return result

    async def authorization(state, parameters):
        client = await client_for(state)
        business = client.business_application(state.applicationId)
        operation, role = parameters.get("operation"), parameters.get("roleId")
        if operation == "roleAuthorization":
            return await collect(business.role_authorization(role))
        if operation == "roleMenus":
            return await collect(business.role_menus(role))
        if operation == "assets":
            return await business.assets(role)
        identity, fingerprint = id_of(state), sha256(encode(state).encode())
        existing = snapshots.get(identity)
        if existing is None or existing[0] != fingerprint:

            async def load():
                members, roles, user_roles, grants = await asyncio.gather(
                    collect(business.members()),
                    collect(business.roles()),
                    collect(business.user_roles()),
                    collect(business.grants()),
                )
                yield {
                    "members": members,
                    "roles": roles,
                    "userRoles": user_roles,
                    "grants": grants,
                }

            existing = fingerprint, SnapshotSynchronizer(load, 1)
            snapshots[identity] = existing
        return (await existing[1].refresh())[0]

    def json(value, status=200, headers=None):
        return Response(
            encode(redact(value)),
            status_code=status,
            media_type="application/json",
            headers=headers,
        )

    def bounded_put(mapping, key, value):
        for identity in list(mapping):
            if mapping[identity]["expires"] < time.time():
                del mapping[identity]
        if len(mapping) >= 128:
            raise SdkError("LIMIT_EXCEEDED", "Example login capacity reached")
        mapping[key] = value

    async def endpoint(request):
        try:
            return await handle(request)
        except SdkError as error:
            return json(
                {"error": error.code},
                401
                if error.code == "AUTHENTICATION"
                else error.status or (429 if error.code == "LIMIT_EXCEEDED" else 400),
            )
        except Exception:
            import logging

            logging.getLogger(__name__).exception("Example request failed")
            return json({"error": "Example request failed"}, 500)

    async def handle(request):
        path = request.url.path
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > 1048576:
                raise SdkError("LIMIT_EXCEEDED", "Request exceeds limit")
            chunks.append(chunk)
        raw = b"".join(chunks)
        headers = {}
        for key, value in request.scope["headers"]:
            headers.setdefault(key.decode("latin1"), []).append(value.decode("latin1"))
        if path.startswith("/jetlinks/events"):
            channel = (
                "" if path == "/jetlinks/events" else path[len("/jetlinks/events/") :]
            )

            def trust(platform, app, key_id):
                from jetlinks_sdk_client import TrustedKey

                if not platform:
                    api = next(
                        (
                            a
                            for a in config["apiApplications"]
                            if a["contextId"] == channel
                            and a["applicationId"] == app
                            and a["accessKey"] == key_id
                        ),
                        None,
                    )
                    return (
                        TrustedKey(
                            "api", api["accessSecret"], channel, applicationId=app
                        )
                        if api
                        else None
                    )
                if channel:
                    return None
                template = next(
                    (
                        t
                        for t in config["templates"]
                        if t["platformId"] == platform
                        and t["notificationKeyId"] == key_id
                    ),
                    None,
                )
                return (
                    TrustedKey(
                        "business",
                        template["notificationSecret"],
                        platformId=platform,
                        templateId=template["templateId"],
                    )
                    if template
                    else None
                )

            class AcceptStore:
                async def accept(self, notice):
                    before = await resolve(notice.platformId, notice.applicationId)
                    fresh = await store.accept(notice)
                    if fresh and notice.key.type == "business":
                        after = await resolve(notice.platformId, notice.applicationId)
                        identity = context_id(after)

                        def connection_state(s):
                            return (
                                (
                                    s.createdAt,
                                    s.apiBaseUrl,
                                    s.accessKey,
                                    s.accessSecret,
                                    s.keyRevision,
                                    s.enabled,
                                    s.deletedAt,
                                )
                                if s
                                else None
                            )

                        if connection_state(before) != connection_state(after):
                            for suffix in ("", ":user", ":management"):
                                await clients.invalidate(identity + suffix)
                                await asyncio.to_thread(
                                    sync_clients.invalidate, identity + suffix
                                )
                        snapshots.pop(identity, None)
                    return fresh

            receiver = NotificationReceiver(NotificationVerifier(trust), AcceptStore())
            try:
                await receiver.receive(headers, raw)
            except SdkError as error:
                if error.code == "AUTHENTICATION":
                    await asyncio.to_thread(
                        store.receipt,
                        {
                            "verified": False,
                            "accepted": False,
                            "rawBody": raw.decode(errors="replace"),
                        },
                    )
                raise
            return Response(status_code=204)
        if path == "/api/capabilities" or path.startswith("/api/commands/"):
            target = request.scope["raw_path"].decode("ascii") + (
                "?" + request.scope["query_string"].decode("ascii")
                if request.scope["query_string"]
                else ""
            )
            result = await commands.handle(request.method, target, headers, raw)

            async def output():
                try:
                    async for item in result.body:
                        yield item
                finally:
                    await result.close()

            return StreamingResponse(
                output(), status_code=result.status, headers=result.headers
            )
        parameters = decode(raw) if raw else {}
        if path == "/example-api/info":
            return json(
                {
                    "language": "python",
                    "sdkVersion": "2.0.0a0",
                    "features": [
                        "requests",
                        "notifications",
                        "login",
                        "authorization",
                        "subscriptions",
                        "commands",
                    ],
                    "clientModes": [
                        {"value": "async", "label": "AsyncJetLinksClient"},
                        {"value": "sync", "label": "JetLinksClient"},
                    ],
                    "syncStatus": "on-demand",
                }
            )
        if path == "/example-api/applications":
            return json([public_state(state) for state in await states()])
        if path == "/example-api/notifications":
            return json(
                await asyncio.to_thread(store.receipts, dict(request.query_params))
            )
        if path.startswith("/example-api/notifications/"):
            return json(await asyncio.to_thread(store.detail, path.rsplit("/", 1)[1]))
        if path == "/example-api/session":
            identity = request.cookies.get("jl-session")
            if request.method == "DELETE":
                sessions.pop(identity, None)
                return json(
                    {"authenticated": False},
                    headers={
                        "Set-Cookie": "jl-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                    },
                )
            session = sessions.get(identity)
            return json(
                {
                    "authenticated": True,
                    "contextId": session["contextId"],
                    "context": session["context"],
                }
                if session
                and session["expires"] > time.time()
                and (await find(session["contextId"])).active
                else {"authenticated": False}
            )
        if path == "/example-api/login/callback":
            flow = pending.pop(request.cookies.get("jl-pending"), None)
            query = request.query_params
            if (
                not flow
                or flow["expires"] < time.time()
                or (not flow["platform"] and query.get("state") != flow["auth"].state)
                or flow["platform"]
                and (
                    query.get("platformId") != flow["state"].platformId
                    or query.get("applicationId") != flow["state"].applicationId
                )
            ):
                raise SdkError("AUTHENTICATION", "Invalid login state")
            state = await find(flow["contextId"])
            if (
                not state.active
                or state.createdAt != flow["state"].createdAt
                or state.keyRevision != flow["state"].keyRevision
            ):
                raise SdkError("AUTHENTICATION", "Application changed during login")
            oauth = AsyncOAuth2(state.apiBaseUrl, state.accessKey, state.accessSecret)
            try:
                token = await oauth.exchange(
                    query.get("code"),
                    flow["auth"].redirectUri,
                    None if flow["platform"] else flow["auth"].verifier,
                )
                async with AsyncJetLinksClient(
                    state.apiBaseUrl, token["access_token"]
                ) as user:
                    context = await user.authorization_context()
                if (
                    context.application.get("id") != state.applicationId
                    or context.subject.get("type") != "user"
                    or not context.subject.get("id")
                ):
                    raise SdkError("AUTHENTICATION", "Authorization context mismatch")
                identity = str(uuid.uuid4())
                bounded_put(
                    sessions,
                    identity,
                    {
                        "contextId": flow["contextId"],
                        "context": context,
                        "expires": time.time() + min(900, token["expires_in"]),
                    },
                )
                return Response(
                    status_code=302,
                    headers={
                        "Location": "/",
                        "Set-Cookie": f"jl-session={identity}; HttpOnly; SameSite=Lax; Path=/; Max-Age=900",
                    },
                )
            finally:
                await oauth.close()
        prefix = "/example-api/applications/"
        if path.startswith(prefix):
            parts = path[len(prefix) :].split("/", 1)
            state, action = await find(parts[0]), parts[1] if len(parts) > 1 else None
            if not action:
                return json(public_state(state))
            if not state.active:
                raise SdkError("INVALID_ARGUMENT", "Inactive application")
            if action == "commands":
                return json(
                    {
                        "capabilities": commands.capabilities(state),
                        "records": [
                            r
                            for r in records
                            if r["platformId"] == state.platformId
                            and r["applicationId"] == state.applicationId
                        ],
                    }
                )
            if action == "authorization":
                return json(await authorization(state, parameters))
            if action == "login":
                oauth = AsyncOAuth2(
                    state.apiBaseUrl, state.accessKey, state.accessSecret
                )
                try:
                    auth = oauth.authorization(
                        config["publicBaseUrl"].rstrip("/")
                        + "/example-api/login/callback"
                    )
                    platform = parameters.get("entry") == "platform"
                    async with AsyncJetLinksClient(
                        state.apiBaseUrl, configured_token(state, "user")
                    ) as user:
                        url = urlsplit(auth.url)
                        location = (
                            (
                                await user.request(
                                    "/business-application/"
                                    + segment(state.applicationId)
                                    + "/_redirect"
                                )
                            )["location"]
                            if platform
                            else await user.request(
                                "/oauth2/authorize", query=dict(parse_qsl(url.query))
                            )
                        )
                    identity = str(uuid.uuid4())
                    bounded_put(
                        pending,
                        identity,
                        {
                            "contextId": id_of(state),
                            "state": replace(state),
                            "auth": auth,
                            "platform": platform,
                            "expires": time.time() + 600,
                        },
                    )
                    return json(
                        {"url": location},
                        headers={
                            "Set-Cookie": f"jl-pending={identity}; HttpOnly; SameSite=Lax; Path=/; Max-Age=600"
                        },
                    )
                finally:
                    await oauth.close()
            mode = (
                "management"
                if action == "subscriptions"
                else parameters.get("authMode", "oauth2")
            )
            client = await client_for(state, mode)
            if action == "diagnostics":
                return json(
                    {
                        "tokenRefreshes": getattr(
                            client.token_provider, "refreshes", 0
                        ),
                        "tokenCacheHits": getattr(client.token_provider, "hits", 0),
                        "commandsInFlight": commands.in_flight,
                        "syncStatus": "on-demand",
                        "snapshotAvailable": id_of(state) in snapshots,
                    }
                )
            if action == "subscriptions":
                subscriptions = client.subscriptions(state.applicationId)
                identity, operation = (
                    parameters.get("id"),
                    parameters.get("operation", "query"),
                )
                calls = {
                    "query": lambda: subscriptions.query(),
                    "get": lambda: subscriptions.get(identity),
                    "create": lambda: subscriptions.create(parameters.get("body")),
                    "update": lambda: subscriptions.update(
                        identity, parameters.get("body")
                    ),
                    "validate": lambda: subscriptions.validate(parameters.get("body")),
                    "enable": lambda: subscriptions.enable(identity),
                    "disable": lambda: subscriptions.enable(identity, False),
                    "delete": lambda: subscriptions.delete(identity),
                    "events": subscriptions.events,
                    "channels": subscriptions.channels,
                    "tasks": lambda: subscriptions.tasks(identity),
                    "records": lambda: subscriptions.records(identity),
                    "payload": lambda: subscriptions.payload(
                        identity, parameters.get("taskId"), parameters.get("attempt")
                    ),
                }
                if operation not in calls:
                    raise SdkError("INVALID_ARGUMENT", "Unknown subscription operation")
                return json(await calls[operation]())
            if action == "requests":
                options = {
                    "method": parameters.get("method", "GET"),
                    "body": parameters.get("body"),
                }
                route = parameters.get("path", "/authorize/context")
                if parameters.get("stream"):

                    async def output():
                        async with contextlib.aclosing(
                            client.stream(route, **options)
                        ) as source:
                            async for item in source:
                                yield encode(redact(item)).encode() + b"\n"

                    return StreamingResponse(
                        output(), media_type="application/x-ndjson"
                    )
                start = time.monotonic()
                if parameters.get("client") == "sync":

                    def execute():
                        sync = sync_clients.get(
                            id_of(state) + (":" + mode if mode == "user" else ""),
                            state,
                            "bearer" if mode == "user" else mode,
                            bearer=configured_token(state, mode),
                        )
                        return sync.request(route, **options)

                    value = await asyncio.to_thread(execute)
                else:
                    value = await client.request(route, **options)
                return json(
                    {
                        "durationMs": int((time.monotonic() - start) * 1000),
                        "result": value,
                    }
                )
        return json({"error": "Unknown route"}, 404)

    @contextlib.asynccontextmanager
    async def lifespan(_app):
        yield
        await clients.close()
        await asyncio.to_thread(sync_clients.close)
        await asyncio.to_thread(store.close)

    methods = ["GET", "POST", "PUT", "DELETE"]
    return Starlette(
        routes=[
            Route("/example-api/{path:path}", endpoint, methods=methods),
            Route("/jetlinks/events", endpoint, methods=["POST"]),
            Route("/jetlinks/events/{channel:path}", endpoint, methods=["POST"]),
            Route("/api/{path:path}", endpoint, methods=methods),
            Mount("/", StaticFiles(directory=config["uiDir"], html=True)),
        ],
        lifespan=lifespan,
    )


if __name__ == "__main__":
    config = decode(
        pathlib.Path(
            sys.argv[1] if len(sys.argv) > 1 else "example.local.json"
        ).read_text()
    )
    uvicorn.run(
        create_app(config),
        host=config.get("host", "127.0.0.1"),
        port=config["port"],
        log_level="warning",
    )
