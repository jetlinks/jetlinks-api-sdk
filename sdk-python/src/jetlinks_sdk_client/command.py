"""框架无关的第三方命令服务；签名绑定 Header，正文完整性由 HTTPS 提供。"""

import asyncio
import inspect
import logging
import re
import time
from dataclasses import dataclass, replace
from urllib.parse import unquote

from .models import model_from_dict
from .protocol import (
    Headers,
    SdkError,
    check_headers,
    decode,
    encode,
    header,
    matches,
    sign,
)


@dataclass
class CommandResponse:
    status: int
    headers: dict
    body: object
    release: object

    async def close(self):
        self.release()
        await self.body.aclose()


class CommandService:
    """处理器只收到可信应用副本。on_error 供服务端诊断，不能将完整异常返回客户端。"""

    def __init__(self, resolver, concurrency=8, timeout=30):
        if min(concurrency, timeout) < 0.001:
            raise SdkError("INVALID_ARGUMENT", "Invalid command limits")
        self.timeout = timeout
        self.resolver, self.concurrency = resolver, concurrency
        self.definitions, self.budgets, self.in_flight = {}, {}, 0
        self.on_error = lambda failure: logging.getLogger(__name__).error(
            "JetLinks command failed: %s", failure
        )

    def register(
        self, identity, stream, handler, model=None, allowed=lambda state: True
    ):
        if not identity or identity in self.definitions:
            raise SdkError("INVALID_ARGUMENT", "Duplicate or invalid command")
        self.definitions[identity] = stream, handler, model, allowed
        return self

    def capabilities(self, application):
        return sorted(
            identity
            for identity, (_, _, _, allowed) in self.definitions.items()
            if allowed(application)
        )

    async def handle(self, method, target, headers, body):
        check_headers(headers)
        platform, app, key = (
            header(headers, Headers.PLATFORM),
            header(headers, Headers.APPLICATION),
            header(headers, Headers.KEY),
        )
        request_id, signature = (
            header(headers, Headers.REQUEST),
            header(headers, Headers.SIGNATURE),
        )
        state = self.resolver(platform, app)
        if inspect.isawaitable(state):
            state = await state
        if (
            not state
            or not state.active
            or state.platformId != platform
            or state.applicationId != app
            or state.accessKey != key
            or not re.fullmatch(r"[!-~]{1,128}", platform)
            or not re.fullmatch(r"[a-f0-9]{32}", request_id)
        ):
            raise SdkError("AUTHENTICATION", "Inactive or invalid command identity")
        state = replace(state)
        expected = sign(
            state.accessSecret,
            [
                "http-command",
                "1",
                "request",
                app,
                platform,
                key,
                header(headers, Headers.TIMESTAMP),
                request_id,
                method,
                target,
                header(headers, "Content-Type", False),
                header(headers, "Accept", False),
            ],
        )
        if not matches(expected, signature):
            raise SdkError("AUTHENTICATION", "Command signature rejected")
        identity, released, acquired = (platform, app), False, False

        def release():
            nonlocal released
            if acquired and not released:
                released = True
                self.in_flight -= 1
                self.budgets[identity] -= 1
                if not self.budgets[identity]:
                    del self.budgets[identity]

        def report(command_id, cause):
            try:
                self.on_error(
                    {
                        "platformId": platform,
                        "applicationId": app,
                        "requestId": request_id,
                        "commandId": command_id,
                        "cause": cause,
                    }
                )
            except Exception:
                pass  # 诊断回调不能改变响应与资源释放。

        def respond(status, streaming, data, command_id=""):
            content_type = "application/x-ndjson" if streaming else "application/json"
            stamp = str(int(time.time() * 1000))
            signed = {
                Headers.VERSION: "1",
                Headers.PLATFORM: platform,
                Headers.APPLICATION: app,
                Headers.KEY: key,
                Headers.TIMESTAMP: stamp,
                Headers.REQUEST: request_id,
                "Content-Type": content_type,
            }
            signed[Headers.SIGNATURE] = sign(
                state.accessSecret,
                [
                    "http-command",
                    "1",
                    "response",
                    app,
                    platform,
                    key,
                    stamp,
                    request_id,
                    signature,
                    str(status),
                    content_type,
                ],
            )
            raw = None if streaming else encode(data).encode()
            if raw is not None and len(raw) > 1048576:
                raise SdkError("LIMIT_EXCEEDED", "Command result exceeds limit")

            async def output():
                try:
                    if not streaming:
                        yield raw
                    else:
                        iterator = data.__aiter__()
                        try:
                            while True:
                                try:
                                    item = await asyncio.wait_for(
                                        anext(iterator), self.timeout
                                    )
                                except StopAsyncIteration:
                                    break
                                value = encode(item).encode()
                                if len(value) > 1048576:
                                    raise SdkError(
                                        "LIMIT_EXCEEDED", "Command result exceeds limit"
                                    )
                                yield value + b"\n"
                        finally:
                            if hasattr(iterator, "aclose"):
                                await iterator.aclose()
                except Exception as cause:
                    report(command_id, cause)
                    raise
                finally:
                    release()

            return CommandResponse(status, signed, output(), release)

        if self.budgets.get(identity, 0) >= self.concurrency:
            return respond(429, False, {"error": "Command failed"})
        if len(self.budgets) >= 1024 and identity not in self.budgets:
            return respond(503, False, {"error": "Command failed"})
        acquired = True
        self.budgets[identity] = self.budgets.get(identity, 0) + 1
        self.in_flight += 1
        command_id = ""
        try:
            path = target.split("?", 1)[0]
            if method == "GET" and path == "/api/capabilities":
                return respond(
                    200,
                    False,
                    [{"version": "1", "capabilities": self.capabilities(state)}],
                )
            if method != "POST" or not path.startswith("/api/commands/1/"):
                return respond(404, False, {"error": "Command failed"})
            command_id = unquote(path[len("/api/commands/1/") :])
            definition = self.definitions.get(command_id)
            if not definition or not definition[3](state):
                return respond(404, False, {"error": "Command failed"})
            streaming, handler, model, _ = definition
            parameters = decode(body) if body else {}
            if not isinstance(parameters, dict):
                return respond(400, False, {"error": "Command failed"})
            parameters.update(applicationId=app, templateId=state.templateId)
            result = handler(
                replace(state),
                model_from_dict(model, parameters) if model else parameters,
            )
            if inspect.isawaitable(result):
                result = await asyncio.wait_for(result, self.timeout)
            return respond(200, streaming, result, command_id)
        except asyncio.CancelledError:
            release()
            raise
        except Exception as cause:
            report(command_id, cause)
            return respond(
                400
                if isinstance(cause, SdkError) and cause.code == "SERIALIZATION"
                else 500,
                False,
                {"error": "Command failed"},
            )
