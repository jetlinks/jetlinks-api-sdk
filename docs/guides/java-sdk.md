# Java 接入指南

## 1. 准备凭据

| 应用 | 首次配置 | 调用平台的凭据 |
| --- | --- | --- |
| API 应用 | 可信 API 地址、应用 ID、Access Key/Secret | 已配置的 Key/Secret |
| 第三方业务应用 | 模板 platformId、模板 ID、通知 keyId/Secret、RSA 接收私钥 | 从验签后的生命周期通知解密取得 |

业务应用按 `(platformId, applicationId)` 保存记录，API 地址使用通知中的 `data.application.apiBaseUrl`。`platformId` 对应平台模板配置，未配置时平台默认使用模板 ID。SDK 不发现区域，也不要求额外运维配置服务。

API 应用没有 `X-Platform-Id`；连接多个平台且应用 ID 重复时，以本地配置的通道/地址隔离客户端和通知接收地址。

## 2. 调用平台

引入 `org.jetlinks.sdk:sdk-core:2.0.0-SNAPSHOT`。客户端类型在 `org.jetlinks.sdk`，认证在 `auth`，业务 DTO 在 `model`，事件在 `event`，订阅模型在 `subscription`。

```java
// 本地生成平台支持的二进制 at_ 机器 Token，无需访问 /oauth2/token。
CachedTokenProvider tokens = OAuth2.localTokens(key, secret, null, Duration.ofMinutes(2));
JetLinksReactiveClient reactive = new JetLinksReactiveClient(apiBaseUrl, tokens);
Mono<AuthorizationContext> result = reactive.request(
    ApiRequest.get("/authorize/context"), AuthorizationContext.class);

// 普通线程使用同步门面；它复用同一个 reactive 客户端和认证缓存。
JetLinksClient client = new JetLinksClient(reactive);
Page<Device> page = client.request(ApiRequest.post("/device-instance/_query",
    new Query().eq("state", "online").page(0, 20).sort("id", "asc")),
    new TypeRef<Page<Device>>() {});

// 外层服务停止时关闭客户端；包装同一客户端时只需统一关闭一次。
reactive.close();
```

替换为 OAuth2 或已有 Token：

```java
OAuth2 oauth = new OAuth2(apiBaseUrl, key, secret, true); // true=Basic，false=Post
JetLinksReactiveClient client = new JetLinksReactiveClient(apiBaseUrl, oauth.machineTokens(null));
// 或 new JetLinksReactiveClient(apiBaseUrl, TokenProvider.bearer(existingToken))
// 服务停止时同时关闭 client 和 oauth。
```

机器 Token 自动缓存，同一次并发刷新共用一次申请；失败有冷却时间。凭据或 API 地址变化后关闭旧客户端并重新创建。Scope 的 `null` 表示平台默认，空列表表示明确不申请 Scope。

自签 Token 仍由平台验证密钥、有效期和权限，不是 JWT，也不携带自定义用户身份。Secret 仅放服务端，使用 HTTPS；有效期应与平台配置一致（通常 2 分钟），保持时钟同步。它的安全边界与共享密钥认证一致，无法替代用户登录。

请求路径相对可信 API 地址拼接，保留网关前缀；动态路径段使用 `ApiRequest.segment(id)`，query 使用 `.query(name, value)`。默认解包平台的 `result`，原生响应使用 `.raw()`，完整状态和 Header 使用 `exchange()`。`SdkException` 保留 HTTP 状态和错误 JSON；SDK 不自动重放 401 写请求。

泛型及流：

```java
public class Device { public String id, name; } // 按所需业务字段定义自己的 DTO
Mono<List<Device>> values = reactive.request(
    ApiRequest.get("/some/list"), new TypeRef<List<Device>>() {});
Flux<Device> allPages = reactive.pages("/device-instance/_query", new Query(), Device.class);
Flux<Device> stream = reactive.stream(ApiRequest.get("/some/stream"), Device.class);
// 同步流必须关闭，提前结束会取消上游请求。
try (Stream<Device> rows = client.stream(ApiRequest.get("/some/stream"), Device.class)) {
    rows.limit(10).forEach(System.out::println);
}
```

默认单次请求/逐条流空闲超时 30 秒、聚合正文/单条 NDJSON 上限 1 MiB、连接上限 32、等待队列 128。自定义限制或连接池使用 `JetLinksReactiveClient(base, auth, externalHttpClient, timeout, maxBytes)`；外部连接池由调用方关闭。同步方法不能在 Reactor/Netty 非阻塞线程调用。

两个客户端的请求、流、分页均接受 `Class<T>` 或 `TypeRef<T>`；嵌套泛型使用后者。固定业务接口直接返回 SDK 模型，自定义业务接口可用自己的 DTO；不传类型的通用入口保留 `JsonNode`，用于调试和未知报文。

## 3. 接收通知与生命周期

接收原始正文及多值 Header，先验签，再事务受理，最后返回 2xx。不要先转换 JSON 再序列化参与验签。

```java
NotificationVerifier verifier = new NotificationVerifier((platform, app, keyId) -> {
    // 从本地配置/已绑定归属查找；核对 platform/app/keyId 后返回可信密钥。
    return trustedKeys.resolve(platform, app, keyId);
});
NotificationReceiver receiver = new NotificationReceiver(verifier, durableStore);
Mono<Boolean> accepted = receiver.receive(headers, originalBytes);
```

`trustedKeys`、`durableStore` 为应用实现：API 返回 `TrustedKey.api(appId, secret, localChannelId)`；业务应用返回 `TrustedKey.business(platformId, templateId, appId, notificationSecret)`。这些对象必须来自本地信任配置，不能从通知正文构造。

两个通知通道使用同一套 Header/签名规则：应用 ID 来自验签后的 `X-Application-Id`，业务应用额外携带 `X-Platform-Id`。`subject` 是事件主体；`event.data` 是业务数据，都不能用于决定当前调用的是哪个应用。

`VerifiedNotification.event` 是 `StructuredEvent<Map<String, Object>>`。处理已知事件时直接转换数据类型：

```java
if (EventTypes.APPLICATION_CREDENTIAL_SYNCED.equals(notice.event.type)) {
    BusinessApplicationEventData<LifecyclePayload> data = notice.data(
        new TypeRef<BusinessApplicationEventData<LifecyclePayload>>() {});
    long revision = data.payload.credential.keyRevision;
    // 受理流程仍由 ApplicationLifecycle.apply 负责合并和解密。
}
```

`EventTypes` 定义平台已知事件类型；目录事件的定义 ID 与通知 `type` 一致，能否订阅以实时目录为准。每次发生的事件实例 `id` 由平台生成。自定义事件继续使用字符串和自己的 DTO。签名 Header 名称使用 `ProtocolHeaders` 常量。新增服务端字段可从支持扩展的 DTO 的 `extensions()` 读取。

`NotificationStore.accept` 在一个事务中完成：

1. 业务按 `(platformId, applicationId, eventId)` 去重；API 加入本地可信通道。
2. 核对首次模板绑定或已有归属；调用 `ApplicationLifecycle.apply(previous, notice, privateKeyResolver)` 计算新状态。
3. 保存 inbox、应用状态及后续工作记录，提交后返回成功。重复已持久事件返回 `false`，HTTP 仍 ACK；存储失败返回非 2xx 让平台重投。

生命周期合并处理启停、删除、迟到、同 ID 重建及凭据版本。新的创建事件开启独立生命周期，即使旧删除通知尚未到达，凭据版本也从新生命周期重新比较。RSA 解密采用 OAEP-SHA256/MGF1-SHA256，私钥为 PKCS#8 PEM。`credential.synced` 用于恢复平台当前凭据，不会产生新 Key；轮换须由平台显式操作触发。API 地址、凭据、启停状态变化时应用应淘汰相关客户端/缓存。

示例实现见 `sdk-java/examples/example-common/.../ExampleStore.java`：H2 事务受理，JDBC 在阻塞专用线程执行。生产环境可使用自己的数据库和事务机制。

## 4. 用户登录与授权事实

```java
OAuth2.Authorization pending = oauth.authorization(actualRedirectUri, null);
// 在浏览器会话保存 pending.state/verifier/redirectUri；平台授权接口需已登录用户身份。
// GET pending.url 的平台响应为 JSON result 中的地址，应用再让浏览器跳转。
// 回调时先原子消费 state，再用原始 redirectUri 换 Code，不能用带 code 的回调 URL。
Mono<OAuth2Token> token = oauth.exchange(code, pending.redirectUri, pending.verifier);
```

用返回的用户 Token 查询 `/authorize/context`，核对应用及主体后建立本地会话。回调中的平台/应用标识只能定位已可信绑定的应用记录；不能接受回调传入的 API 地址/Secret。Code 不缓存、不自动重试；平台不发 Refresh Token，过期后重新登录。平台 `_redirect` 入口不自动生成 state/PKCE，仍需由应用关联发起会话，不能仅凭 Code 参数建立会话。

`BusinessApplications` 的成员、角色、关系、API 授权、角色权限和菜单分别返回 `Member`、`Role`、`UserRole`、`ApiGrant`、`RoleAuthorization`、`RoleMenu` 流；`assets(role)` 返回 `List<AssetAccesses>`。关系通知只触发重读完整事实，不当作权限快照；SDK 不计算平台有效权限。

`SnapshotSynchronizer` 在订阅时合并在途同步，重复订阅 `refresh()` 返回值也遵循相同规则；全部读取成功后再替换快照，失败保留上次结果并冷却 1 秒。单个调用方取消不会中断共享同步。Example 演示启动/定期同步和通知触发。

## 5. API 订阅与第三方命令

```java
Subscriptions subscriptions = new Subscriptions(explicitManagementClient, applicationId);
SubscriptionConfig config = new SubscriptionConfig();
config.name = "事件订阅";
config.channelProvider = DeliveryChannel.HTTP_HOOK;
SubscriptionScope scope = new SubscriptionScope();
scope.eventTypes.add(selectedEvent.id); // 从 subscriptions.events() 的当前目录选择
config.scopes.add(scope);
config.channelConfiguration = channelConfiguration; // 按当前渠道要求配置
Mono<String> draftId = subscriptions.create(config); // 新建为停用草稿
// validate → enable → 接收通知 → tasks/records/payload → disable/delete

CommandService commands = new CommandService(applicationStore::find, 8)
    .register("Echo", false, Echo.class,
        (application, parameters) -> Mono.just(parameters.message));
public class Echo { public String message; }
```

订阅管理客户端必须显式具备平台管理权限，普通应用 Token 不保证可管理订阅。方法包括查询、详情、创建、全量更新、启停、删除、校验、目录及投递查询。

创建返回 ID，更新/启停/删除返回影响条数；详情、分页、校验、事件/渠道目录和投递结果均为对应 DTO。命令可用 `Class<T>` 或 `TypeRef<T>` 注册参数类型，未指定类型时参数为 Map；能力版本声明使用 `CommandCapabilities`。

HTTP 适配器把 `GET /api/capabilities`、`POST /api/commands/1/{commandId}` 的最终 method/raw target/多值 Header/正文交给 `commands.handle(...)`，按结果的 status/headers/body 写出。能力来自注册表，可按应用过滤；metadata 可选，未知命令不会执行。响应成功和错误均签名，流取消后释放并发名额；写出失败或未订阅正文时也须 `Response.close()`。

命令执行、流式输出或序列化失败时，默认通过 `org.jetlinks.sdk.command.CommandService` 的 JDK Logger 记录原始异常。可用 `commands.onError(failure -> ...)` 接入自己的日志/追踪，`failure` 提供平台、应用、请求及命令 ID 和 `cause`；回调须非阻塞。原始异常按服务端日志策略处理，不返回给调用方；回调抛出运行时异常不会改变签名错误或阻止名额释放。

命令中的应用身份来自已验签 Header 和已保存实例凭据；处理器收到的保留 `applicationId/templateId` 由 SDK 填充。JSON/NDJSON 正文不聚合做签名，正文完整性依赖 HTTPS。请求 ID 用于排查，涉及写操作的业务幂等由处理器负责。

## 6. Spring Boot

引入 `org.jetlinks.sdk:sdk-spring-boot-starter:2.0.0-SNAPSHOT`，并使用应用已有的 WebFlux 或 MVC Starter。

```yaml
jetlinks:
  sdk:
    base-url: https://example.com/jetlinks
    key: ${JETLINKS_KEY}
    secret: ${JETLINKS_SECRET}
    auth-mode: oauth2 # 或 local
    basic: true
    request-timeout: 30s
    max-response-bytes: 1048576
    local-token-ttl: 2m
```

自动提供两个客户端，可声明自己的 `TokenProvider` 或客户端 Bean 替换。仅在显式提供 `NotificationVerifier`、`NotificationStore` 时开放通知端点；仅在提供 `CommandService` 时开放命令端点。端点可配置 `notification-path`、`command-base-path`，默认 `/jetlinks/events`、`/api`。

正式支持 Boot 2.7 / Java 8。当前 MVC 适配依赖 `javax.servlet`，Boot 3 不在本次支持范围；Boot 3 应用可直接使用 core 实现自己的 HTTP 适配。
