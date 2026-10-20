"""平台固定契约。字段名沿用线上 JSON；自定义业务可传入自己的 dataclass。"""

from dataclasses import dataclass, field, fields, is_dataclass
from decimal import Decimal
from typing import Any, TypeVar, get_args, get_origin, get_type_hints
import types

T = TypeVar("T")


def model_from_dict(model: type[T], value: Any) -> T:
    if value is None:
        return value
    origin, args = get_origin(model), get_args(model)
    if origin is list:
        return [model_from_dict(args[0], item) for item in value]
    if origin is types.UnionType:
        return model_from_dict(next(t for t in args if t is not type(None)), value)
    if model is Decimal:
        return Decimal(value)
    if not is_dataclass(model):
        return value
    hints = get_type_hints(model)
    names = {f.name for f in fields(model)}
    result = {
        k: model_from_dict(hints.get(k, Any), v) for k, v in value.items() if k in names
    }
    if "extra" in names:
        result["extra"] = {k: v for k, v in value.items() if k not in names}
    return model(**result)


@dataclass
class ApplicationState:
    """持久化应用快照；createdAt 区分同 ID 重建，不能跨生命周期比较版本。"""

    platformId: str = ""
    applicationId: str = ""
    templateId: str | None = None
    apiBaseUrl: str | None = None
    accessKey: str | None = field(default=None, repr=False)
    accessSecret: str | None = field(default=None, repr=False)
    createdAt: int = 0
    keyRevision: int = 0
    updatedAt: int = 0
    deletedAt: int = 0
    enabled: bool = True

    @property
    def active(self) -> bool:
        return bool(
            self.enabled
            and not self.deletedAt
            and self.apiBaseUrl
            and self.accessKey
            and self.accessSecret
        )


@dataclass
class StructuredEvent:
    id: str
    type: str
    data: Any = None
    subject: str | None = None
    occurredAt: int | None = None
    extensions: dict = field(default_factory=dict)
    extra: dict = field(default_factory=dict)


@dataclass
class ApplicationInfo:
    id: str
    templateId: str
    createTime: int = 0
    apiBaseUrl: str | None = None
    state: str | None = None
    extensions: dict = field(default_factory=dict)
    extra: dict = field(default_factory=dict)


@dataclass
class EncryptedCredential:
    accessKey: str
    keyRevision: int
    keyId: str
    encryptedAccessSecret: str = field(repr=False)


@dataclass
class LifecyclePayload:
    credential: EncryptedCredential | None = None
    extra: dict = field(default_factory=dict)


@dataclass
class LifecycleData:
    application: ApplicationInfo
    payload: LifecyclePayload = field(default_factory=LifecyclePayload)
    extra: dict = field(default_factory=dict)


@dataclass
class TrustedKey:
    """只能从本地信任配置构造；channelId 是 API 应用的本地隔离标识。"""

    type: str
    secret: str = field(repr=False)
    channelId: str = ""
    applicationId: str | None = None
    platformId: str | None = None
    templateId: str | None = None


@dataclass
class VerifiedNotification:
    platformId: str
    applicationId: str
    eventId: str
    key: TrustedKey = field(repr=False)
    event: StructuredEvent
    rawBody: bytes = field(repr=False)
    signatureHeaders: dict = field(repr=False)

    def data(self, model):
        return model_from_dict(model, self.event.data)


@dataclass
class Page:
    total: int = 0
    pageIndex: int = 0
    pageSize: int = 100
    data: list = field(default_factory=list)


@dataclass
class AuthorizationContext:
    application: dict = field(default_factory=dict)
    subject: dict = field(default_factory=dict)
    extra: dict = field(default_factory=dict)


@dataclass
class Member:
    id: str = ""
    name: str | None = None
    extra: dict = field(default_factory=dict)


@dataclass
class Role:
    id: str = ""
    applicationId: str | None = None
    extra: dict = field(default_factory=dict)


@dataclass
class UserRole:
    userId: str = ""
    roleId: str = ""
    extra: dict = field(default_factory=dict)


@dataclass
class ApiGrant:
    id: str = ""
    extra: dict = field(default_factory=dict)


@dataclass
class RoleAuthorization(ApiGrant):
    pass


@dataclass
class RoleMenu(ApiGrant):
    pass


@dataclass
class AssetAccesses:
    assetType: str = ""
    extra: dict = field(default_factory=dict)


@dataclass
class SubscriptionConfig:
    name: str = ""
    scopes: list = field(default_factory=list)
    channelProvider: str = ""
    channelConfiguration: dict = field(default_factory=dict)


@dataclass
class Subscription(SubscriptionConfig):
    id: str = ""
    applicationId: str = ""
    state: str = "disabled"


@dataclass
class SubscriptionValidation:
    valid: bool = False
    issues: list = field(default_factory=list)


@dataclass
class DeliveryTask:
    id: str = ""
    subscriptionId: str = ""
    state: str = ""
    extra: dict = field(default_factory=dict)


@dataclass
class DeliveryRecord:
    taskId: str = ""
    attempt: int = 0
    state: str = ""
    extra: dict = field(default_factory=dict)


@dataclass
class DeliveryPayload:
    body: Any = None
    rawBody: str = ""
    contentType: str = ""


@dataclass
class EventDefinition:
    id: str
    name: str = ""
    version: str = ""
    group: str = ""
    description: str = ""
    schema: dict = field(default_factory=dict)


@dataclass
class EventProvider:
    id: str
    name: str = ""
    events: list[EventDefinition] = field(default_factory=list)


@dataclass
class DeliveryChannel:
    id: str


@dataclass
class SubscriptionPage(Page):
    data: list[Subscription] = field(default_factory=list)


@dataclass
class DeliveryTaskPage(Page):
    data: list[DeliveryTask] = field(default_factory=list)


@dataclass
class DeliveryRecordPage(Page):
    data: list[DeliveryRecord] = field(default_factory=list)


@dataclass
class ServiceHealth:
    status: str
    details: dict = field(default_factory=dict)


@dataclass
class ServiceInfo:
    name: str
    version: str
    description: str = ""


@dataclass
class TenantMetric:
    id: str
    name: str = ""
    unit: str = ""
    type: str = "request"
    limitType: str = "quotaTimeLimit"


@dataclass
class ResourceCapability:
    resource: str
    capabilities: list[str]


@dataclass
class TenantResource:
    id: str
    name: str = ""
    metrics: list[TenantMetric] = field(default_factory=list)
    capabilities: list[dict] = field(default_factory=list)


@dataclass
class TenantUsage:
    metricId: str
    usage: Decimal


@dataclass
class ReadTenantUsage:
    metricIds: list[str] = field(default_factory=list)


@dataclass
class TenantLimit:
    metricId: str
    limit: Decimal
    windowDurationMillis: int = 0


@dataclass
class SaveTenantLimits:
    revision: int
    limits: list[TenantLimit]
    capabilities: list[ResourceCapability]


class CommandIds:
    GET_SERVICE_HEALTH = "GetServiceHealth"
    GET_SERVICE_INFO = "GetServiceInfo"
    GET_TENANT_RESOURCES = "GetTenantResources"
    READ_TENANT_USAGE = "ReadTenantUsage"
    SAVE_TENANT_LIMITS = "SaveTenantLimits"


class EventTypes:
    APPLICATION_CREATED = "application.created"
    APPLICATION_UPDATED = "application.updated"
    APPLICATION_DELETED = "application.deleted"
    APPLICATION_ENABLED = "application.enabled"
    APPLICATION_DISABLED = "application.disabled"
    APPLICATION_CREDENTIAL_SYNCED = "application.credential.synced"
    APPLICATION_CREDENTIAL_ROTATED = "application.credential.rotated"
    APPLICATION_USER_BOUND = "application.user.bound"
    APPLICATION_USER_UNBOUND = "application.user.unbound"
    APPLICATION_SYNC_CHANGED = "application.sync.changed"

    @classmethod
    def application_events(cls):
        return {v for k, v in vars(cls).items() if k.startswith("APPLICATION_")}


class ChannelIds:
    HTTP_HOOK = "http-hook"


class ResourceTypes:
    ENTITY = "entity"
    REQUEST = "request"
    TIMESERIES = "timeseries"


class LimitTypes:
    UNLIMITED = "unlimited"
    QUOTA = "quotaLimit"
    WINDOW_QUOTA = "quotaTimeLimit"
    CONCURRENCY = "concurrency"
    RATE = "rateLimit"


__all__ = [
    name
    for name, value in list(globals().items())
    if isinstance(value, type) and getattr(value, "__module__", None) == __name__
]
