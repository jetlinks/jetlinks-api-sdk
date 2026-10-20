"""通知验签及生命周期纯函数；持久受理与 HTTP 框架解耦。"""

import inspect
from dataclasses import replace

from .models import (
    ApplicationState,
    EventTypes,
    StructuredEvent,
    VerifiedNotification,
    model_from_dict,
)
from .protocol import (
    Headers,
    SdkError,
    check_headers,
    decode,
    decrypt,
    header,
    header_values,
    matches,
    sha256,
    sign,
    target,
)


class NotificationVerifier:
    def __init__(self, resolver):
        self.resolver = resolver

    async def verify(self, headers, raw_body):
        """resolver 返回本地 TrustedKey，可同步或异步；不得从正文构造信任关系。"""
        check_headers(headers)
        platform, app = (
            header(headers, Headers.PLATFORM, False),
            header(headers, Headers.APPLICATION),
        )
        key_id, event_id = header(headers, Headers.KEY), header(headers, Headers.EVENT)
        if app != app.strip() or (
            platform
            and (len(platform) > 128 or any(not 33 <= ord(c) <= 126 for c in platform))
        ):
            raise SdkError("AUTHENTICATION", "Invalid application identity")
        key = self.resolver(platform, app, key_id)
        if inspect.isawaitable(key):
            key = await key
        if (
            key is None
            or not key.secret
            or (key.applicationId is not None and key.applicationId != app)
            or (key.type == "api" and (platform or key.applicationId != app))
            or (
                key.type == "business"
                and (not platform or platform != key.platformId or not key.templateId)
            )
            or key.type not in ("api", "business")
        ):
            raise SdkError("AUTHENTICATION", "Unknown key or ownership mismatch")
        expected = sign(
            key.secret,
            [
                "1",
                app,
                platform,
                key_id,
                header(headers, Headers.TIMESTAMP),
                event_id,
                sha256(raw_body),
            ],
        )
        if not matches(expected, header(headers, Headers.SIGNATURE)):
            raise SdkError("AUTHENTICATION", "Notification signature rejected")
        document = decode(raw_body)
        if not isinstance(document, dict) or document.get("id") != event_id:
            raise SdkError("AUTHENTICATION", "Event ID mismatch")
        if key.type == "business":
            application = (document.get("data") or {}).get("application") or {}
            if (
                application.get("id") != app
                or application.get("templateId") != key.templateId
            ):
                raise SdkError("AUTHENTICATION", "Event ownership mismatch")
        return VerifiedNotification(
            platform,
            app,
            event_id,
            key,
            model_from_dict(StructuredEvent, document),
            bytes(raw_body),
            header_values(headers),
        )


class NotificationReceiver:
    """store.accept 必须在单个事务内保存去重记录、绑定与状态；成功后适配器才能返回 2xx。"""

    def __init__(self, verifier, store):
        self.verifier, self.store = verifier, store

    async def receive(self, headers, raw_body):
        notice = await self.verifier.verify(headers, raw_body)
        return await self.store.accept(notice)


def apply_lifecycle(current, notice, private_key):
    """返回副本；createdAt 区分重建，revision 仅比较同一生命周期的凭据。"""

    def fresh():
        return ApplicationState(
            platformId=notice.platformId,
            applicationId=notice.applicationId,
            templateId=notice.key.templateId,
        )

    if current and (
        current.platformId != notice.platformId
        or current.applicationId != notice.applicationId
        or current.templateId != notice.key.templateId
    ):
        raise SdkError("AUTHENTICATION", "Existing ownership conflict")
    state = replace(current) if current else fresh()
    event = notice.event
    if event.type not in EventTypes.application_events():
        return state
    time = event.occurredAt
    if time is None or time < 0:
        raise SdkError("INVALID_ARGUMENT", "Missing lifecycle occurredAt")
    application = event.data["application"]
    created_at = application.get("createTime", 0)
    if created_at < state.createdAt or time < created_at:
        return state
    if created_at > state.createdAt:
        state = fresh()
        state.createdAt = created_at
    if event.type == EventTypes.APPLICATION_DELETED:
        if time >= state.updatedAt:
            state.deletedAt = state.updatedAt = time
            state.enabled, state.accessKey, state.accessSecret = False, None, None
        return state
    if state.deletedAt:
        return state
    if time >= state.updatedAt:
        if application.get("apiBaseUrl") is not None:
            target(application["apiBaseUrl"], "/")
            state.apiBaseUrl = application["apiBaseUrl"]
        if application.get("state") is not None:
            state.enabled = application["state"] == "enabled"
        if event.type == EventTypes.APPLICATION_DISABLED:
            state.enabled = False
        if event.type == EventTypes.APPLICATION_ENABLED:
            state.enabled = True
        state.updatedAt = time
    credential = (event.data.get("payload") or {}).get("credential")
    if (
        event.type
        in (
            EventTypes.APPLICATION_CREATED,
            EventTypes.APPLICATION_CREDENTIAL_SYNCED,
            EventTypes.APPLICATION_CREDENTIAL_ROTATED,
        )
        and credential
    ):
        revision = credential.get("keyRevision", -1)
        if revision < 1:
            raise SdkError("INVALID_ARGUMENT", "Invalid credential revision")
        if not state.accessSecret or revision > state.keyRevision:
            state.accessKey = credential["accessKey"]
            state.accessSecret = decrypt(
                credential["encryptedAccessSecret"], private_key(credential["keyId"])
            )
            state.keyRevision = revision
    return state
