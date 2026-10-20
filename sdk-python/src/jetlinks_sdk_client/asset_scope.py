"""资产选择范围：不同维度取交集，不授予身份之外的权限。"""

from types import MappingProxyType
import inspect

import httpx

from .protocol import SdkError


class AssetScope:
    """配置回调中的构造器；同类型后设置覆盖前设置。"""

    def __init__(self):
        self._values = {}

    def organization(self, organization_id):
        return self.scope("org", organization_id)

    def tenant(self, tenant_id):
        return self.scope("tenant", tenant_id)

    def scope(self, scope_type, scope_id):
        for value, limit in ((scope_type, 64), (scope_id, 256)):
            if (
                not isinstance(value, str)
                or not value
                or len(value.encode("utf-16-le", errors="surrogatepass")) // 2 > limit
                or value != value.strip().strip("\ufeff")
                or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)
            ):
                raise SdkError("INVALID_ARGUMENT", "Invalid asset scope")
        if ":" in scope_type or (
            scope_type not in self._values and len(self._values) >= 16
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid asset scope")
        self._values[scope_type] = scope_id
        return self


def configure_scope(defaults, action):
    builder = AssetScope()
    for scope_type, scope_id in defaults.items():
        builder.scope(scope_type, scope_id)
    if not callable(action):
        raise SdkError("INVALID_ARGUMENT", "Expected asset scope callback")
    result = action(builder)
    if inspect.isawaitable(result):
        # 仅释放尚未启动的原生协程；不取消调用方拥有的 Task/Future 或自定义 awaitable。
        if (
            inspect.iscoroutine(result)
            and inspect.getcoroutinestate(result) == inspect.CORO_CREATED
        ):
            result.close()
        raise SdkError("INVALID_ARGUMENT", "Asset scope callback must be synchronous")
    return MappingProxyType(dict(builder._values))


def scope_headers(defaults, headers, action, accept):
    """先默认值、再原始 Header、最后请求 DSL；每个维度使用独立 Header。"""
    builder = AssetScope()
    for scope_type, scope_id in defaults.items():
        builder.scope(scope_type, scope_id)
    result = httpx.Headers(headers)
    for value in result.get_list("X-Asset-Scope"):
        scope_type, sep, scope_id = value.partition(":")
        if not sep:
            raise SdkError("INVALID_ARGUMENT", "Invalid asset scope")
        builder.scope(scope_type, scope_id)
    values = (
        configure_scope(builder._values, action)
        if action is not None
        else builder._values
    )
    result.pop("X-Asset-Scope", None)
    if "Accept" not in result:
        result["Accept"] = accept
    return list(result.multi_items()) + [
        ("X-Asset-Scope", f"{scope_type}:{scope_id}")
        for scope_type, scope_id in values.items()
    ]
