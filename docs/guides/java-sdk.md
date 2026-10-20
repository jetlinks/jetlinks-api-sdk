# Java 接入指南

运行环境：Java 17+；Spring 应用使用 Spring Boot 3.5。

## 1. 准备凭据

| 应用 | 首次配置 | 调用平台的凭据 |
| --- | --- | --- |
| API 应用 | 可信 API 地址、应用 ID、Access Key/Secret | 已配置的 Key/Secret |
| 第三方业务应用 | 模板 platformId、模板 ID、通知 keyId/Secret、RSA 接收私钥 | 从验签后的生命周期通知解密取得 |

业务应用按 `(platformId, applicationId)` 保存记录，API 地址使用通知中的 `data.application.apiBaseUrl`，保留网关前缀。SDK 与平台均支持 HTTP/HTTPS，生产环境使用 HTTPS。`platformId` 对应平台模板配置，未配置时平台默认使用模板 ID。SDK 不发现区域，也不要求额外运维配置服务。

API 应用没有 `X-Platform-Id`；连接多个平台且应用 ID 重复时，以本地配置的通道/地址隔离客户端和通知接收地址。

## 2. 调用平台

引入 `org.jetlinks.sdk:sdk-client-core:2.0.0-SNAPSHOT`。客户端类型在 `org.jetlinks.sdk.client`，认证在 `auth`，业务 DTO 在 `model`，事件在 `event`，订阅模型在 `subscription`。

```java
// 本地生成平台支持的二进制 at_ 机器 Token，无需访问 /oauth2/token。
JetLinksReactiveClient reactive = JetLinksReactiveClient.local(apiBaseUrl, key, secret);
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

多应用服务使用服务级 `ApplicationClients`，每次业务操作传入持久层的当前状态：

```java
ApplicationClients clients = new ApplicationClients(); // 停服时 close
ApplicationClients.Handle handle = clients.get(localContextId, currentState); // 默认 OAuth2
Mono<AuthorizationContext> result = handle.client.request(
    ApiRequest.get("/authorize/context"), AuthorizationContext.class);
// 普通线程可使用 handle.sync；单次请求不关闭共享 Handle。
```

管理器不缓存第二份应用状态。地址/凭据/生命周期变化时替换连接，禁用/删除时拒绝获取；通知事务提交后可
`invalidate(localContextId)`。API 同名应用及不同用户身份使用不同本地 contextId，认证模式与 Scope 自动隔离。
默认上限 128；`LOCAL`、`OAUTH2`、`BEARER` 可显式选择。返回的客户端只在本次操作内使用，不长期保存旧 Handle。

机器 Token 自动缓存，同一次并发刷新共用一次申请；失败有冷却时间。凭据或 API 地址变化后关闭旧客户端并重新创建。Scope 的 `null` 表示平台默认，空列表表示明确不申请 Scope。

自签 Token 仍由平台验证密钥、有效期和权限，不是 JWT，也不携带自定义用户身份。Secret 仅放服务端，使用 HTTPS；有效期应与平台配置一致（通常 2 分钟），保持时钟同步。它的安全边界与共享密钥认证一致，无法替代用户登录。

请求路径相对可信 API 地址拼接，保留网关前缀；动态路径段使用 `ApiRequest.segment(id)`，query 使用 `.query(name, value)`。默认解包平台的 `result`，原生响应使用 `.raw()`，完整状态和 Header 使用 `exchange()`。SDK 不自动重放 401 写请求。

SDK 自身的错误统一为 `SdkException`，通过 `code` 区分参数、状态、鉴权、编解码、加密、容量、远端和传输错误；通过 `getCause()` 查看原始原因。`REMOTE_ERROR` 的 `status/response` 保留远端状态和正文，本地错误的 `status` 为 0；不要把完整异常或远端正文直接返回给用户。自定义处理器、存储和认证扩展抛出的异常保留原样，响应式客户端通过错误信号传播。

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

`VerifiedNotification.event` 是 `StructuredEvent<Map<String, Object>>`。公开事件上下文通过 `event.extensions` 读取，例如 `tenantId`；业务数据仍在 `event.data`，应用身份来自验签后的 Header。处理已知事件时直接转换数据类型：

```java
if (EventTypes.APPLICATION_CREDENTIAL_SYNCED.equals(notice.event.type)) {
    BusinessApplicationEventData<LifecyclePayload> data = notice.data(
        new TypeRef<BusinessApplicationEventData<LifecyclePayload>>() {});
    long revision = data.payload.credential.keyRevision;
    // 受理流程仍由 ApplicationLifecycle.apply 负责合并和解密。
}
```

`EventTypes` 定义平台已知事件类型；目录事件的定义 ID 与通知 `type` 一致，能否订阅以实时目录为准。每次发生的事件实例 `id` 由平台生成。自定义事件继续使用字符串和自己的 DTO。签名 Header 名称使用 `ProtocolHeaders` 常量。新增服务端字段通过 DTO 的 `additionalProperties()` 读取；公开事件上下文使用 `event.extensions`。

`NotificationStore.accept` 在一个事务中完成：

1. 业务按 `(platformId, applicationId, eventId)` 去重；API 加入本地可信通道。
2. 核对首次模板绑定或已有归属；调用 `ApplicationLifecycle.apply(previous, notice, privateKeyResolver)` 计算新状态。
3. 保存 inbox、应用状态及后续工作记录，提交后返回成功。重复已持久事件返回 `false`，HTTP 仍 ACK；存储失败返回非 2xx 让平台重投。

生命周期合并以 `data.application.createTime` 区分同 ID 应用重建，`occurredAt` 排序同一生命周期的状态，凭据版本也仅在同一生命周期内比较。凭据同步先到也能识别新实例，旧实例通知不能覆盖新状态或密钥；创建通知保留平台实际启停状态。RSA 解密采用 OAEP-SHA256/MGF1-SHA256，私钥为 PKCS#8 PEM。`credential.synced` 恢复平台当前凭据，不生成新 Key；轮换由平台显式操作触发。API 地址、凭据、启停状态变化时应淘汰相关客户端/缓存。

示例实现见 `sdk-java/examples/example-common/.../ExampleStore.java`：H2 事务受理，JDBC 在阻塞专用线程执行。生产环境可使用自己的数据库和事务机制。

## 4. 用户登录与授权事实

### 4.1 接入前准备

用户访问流程已包含在 SDK 和两个 Example 中：SDK 负责生成 state/PKCE、兑换授权码和请求授权上下文；应用负责平台登录入口、浏览器跳转、本地用户映射和会话。

1. 在第三方模板配置 `configuration.redirectUri`，例如 `https://partner.example.com/jetlinks/callback`；生产使用 HTTPS，建议使用不带 fragment 的服务端回调地址。
2. 配置通知并创建业务应用实例，等凭据通知验签、解密、持久化完成。保存 `(platformId, applicationId)`、`apiBaseUrl`、Key/Secret；应用须已启用，访问用户须具备该应用的成员资格。
3. 实现登录发起和回调接口，以及本地会话存储。多实例部署时共享待登录记录和会话，待登录记录须有短有效期并支持原子消费。

两种入口最终都使用用户授权码；应用机器 Token 不能代替平台用户身份：

| 入口 | 平台请求（须携带当前已登录用户身份） | 成功响应 |
| --- | --- | --- |
| 平台应用列表进入 | `GET /business-application/{applicationId}/_redirect` | `result.location` 是第三方回调地址，含 `code/platformId/applicationId` |
| 第三方发起登录 | `GET /oauth2/authorize`，参数由 `OAuth2.authorization` 生成 | `result` 是第三方回调地址，含 `code/state` |

两个接口返回 JSON，调用方读取地址后让浏览器导航，不是直接返回 HTTP 302。SDK 不提供平台登录页；未登录时由接入方对接平台已有登录页面，再由平台侧已登录页面或服务完成上述请求。不要直接把不带用户身份的授权 URL 当成通用登录页，也不要在应用中共用固定的用户 Token。

### 4.2 第三方发起登录（state + PKCE）

流程：浏览器进入应用登录接口 → 保存待登录记录 → 平台用户完成授权 → 浏览器回调 → 兑换 Code → 核验用户 → 建立应用会话。

```java
// application 是第 3 节通知建立的可信 ApplicationState。
OAuth2 oauth = new OAuth2(application.apiBaseUrl,
    application.accessKey, application.accessSecret, true);
String actualRedirectUri = "https://partner.example.com/jetlinks/callback";
OAuth2.Authorization pending = oauth.authorization(actualRedirectUri, null);
// 服务端保存 application 的复合身份及 pending，绑定当前浏览器会话。
// 将 pending.url 交给平台已登录入口请求；verifier 只保存在服务端。
// 得到 {"result":"https://partner.example.com/jetlinks/callback?code=...&state=..."}
// 后，由浏览器导航到 result。
```

回调先核对当前浏览器对应的待登录记录、有效期和 `state`，再原子消费记录；不能只根据 query 的 state 查找记录而忽略浏览器绑定。应用身份从待登录记录取得，标准授权回调不保证附加 `platformId/applicationId`。业务应用实际回调可选择模板注册地址同源的 path/query，不能带 fragment；兑换须使用申请时保存的完整 `redirectUri`。

```java
// 已完成 state/浏览器绑定核验；application、pending 均从服务端记录恢复。
Mono<AuthorizationContext> authenticated = oauth
    .exchange(code, pending.redirectUri, pending.verifier)
    .flatMap(token -> {
        JetLinksReactiveClient user = new JetLinksReactiveClient(
            application.apiBaseUrl, TokenProvider.bearer(token.accessToken));
        return user.request(ApiRequest.get("/authorize/context"), AuthorizationContext.class)
            .map(context -> {
                if (context.application == null
                    || !application.applicationId.equals(context.application.id)
                    || context.subject == null || context.subject.id == null
                    || context.subject.id.isEmpty()) {
                    throw new SdkException(SdkException.Code.AUTHENTICATION,
                        "Authorization context mismatch");
                }
                // 按 platformId + applicationId + subject.id 映射本地用户。
                // 创建本地会话；需继续调用用户 API 时，将 token 安全保存在服务端。
                return context;
            })
            .doFinally(signal -> user.close());
    });
// 在应用服务停止时关闭复用的 oauth；按请求创建时在链路结束后关闭。
```

普通线程也可用 `JetLinksClient` 查询同一上下文；WebFlux/Netty 回调中保持响应式链路。`subject.name` 是显示名，不能作为唯一用户标识；需用户名时用同一用户 Token 查询 `/user/detail`，核对返回的用户 ID 后读取 username。`extensions.tenant` 为可选扩展，不能用回调参数指定租户归属。

### 4.3 从平台进入第三方应用

平台页面以当前用户身份获取访问地址：

```http
GET /business-application/partner-app-001/_redirect
Authorization: Bearer <当前平台用户 Token>
```

```json
{
  "result": {
    "location": "https://partner.example.com/jetlinks/callback?platformId=east&applicationId=partner-app-001&code=..."
  }
}
```

平台页面导航到 `result.location`。query 的 `(platformId, applicationId)` 只能定位已可信绑定的记录。若双方已有能把本次 Code 与当前浏览器可靠绑定的机制，可从该记录读取地址和凭据，用 `oauth.exchange(code, registeredRedirectUri, null)` 兑换，再按 4.2 查询上下文并建立会话。`registeredRedirectUri` 是模板原始 `configuration.redirectUri`，保留原有 query，不包含平台本次追加的参数。

`_redirect` 不自动生成 state/PKCE，单有待登录 Cookie 也不能证明 Code 属于本次浏览器授权。建议将首次平台到访作为登录入口，按 4.2 统一重新发起带 state/PKCE 的用户授权；不能仅凭收到的 Code 就建立浏览器会话。模板附加的 userId/username 只作业务提示，身份以兑换后的上下文为准。回调参数不能提供或覆盖 API 地址、Secret 和用户身份。

### 4.4 会话、后续请求与授权同步

给浏览器设置不透明的本地会话 Cookie（生产使用 `HttpOnly; Secure; SameSite=Lax`），Token/Secret 留在服务端，登录完成后跳转到不含 Code 的页面。用户请求使用该用户的 Token，后台任务使用第 2 节机器 Token，缓存按应用和用户隔离。Code 只兑换一次，不缓存、不自动重试；超时且结果不明时重新授权。平台不发 Refresh Token，Token 过期或返回 401 后重新登录；403 按当前权限处理。

本地会话期限由应用管理，建议不超过用户 Token 的有效期。本地退出清理自己的会话和服务端 Token，不代表退出平台；成员/权限回收不会自动删除应用会话，应结合通知刷新授权并及时失效相关会话。

`BusinessApplications` 的成员、角色、关系、API 授权、角色权限和菜单分别返回 `Member`、`Role`、`UserRole`、`ApiGrant`、`RoleAuthorization`、`RoleMenu` 流；`assets(role)` 返回 `List<AssetAccesses>`。关系通知只触发重读完整事实，不当作权限快照；SDK 不计算平台有效权限。

`SnapshotSynchronizer` 在订阅时合并在途同步，重复订阅 `refresh()` 返回值也遵循相同规则；全部读取成功后再替换快照，失败保留上次结果并冷却 1 秒。单个调用方取消不会中断共享同步。通过 `current()` 读取最后成功结果，无需额外缓存；应用凭据、地址或启停状态变化时移除旧同步器。Example 演示启动/定期同步和通知触发。

可运行实现见 [Example 配置与验证](examples.md#用户访问与登录验证)，核心流程在 [ExampleService](../../sdk-java/examples/example-common/src/main/java/org/jetlinks/sdk/client/example/ExampleService.java) 的 `login`、`callback`；应用可复用 SDK 实现自己的控制器和会话，不需要依赖 Example。

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

命令执行、流式输出或序列化失败时，默认通过 `org.jetlinks.sdk.client.command.CommandService` 的 JDK Logger 记录原始异常。可用 `commands.onError(failure -> ...)` 接入自己的日志/追踪，`failure` 提供平台、应用、请求及命令 ID 和 `cause`；回调须非阻塞。原始异常按服务端日志策略处理，不返回给调用方；回调抛出运行时异常不会改变签名错误或阻止名额释放。

命令中的应用身份来自已验签 Header 和已保存实例凭据；处理器收到的保留 `applicationId/templateId` 由 SDK 填充。JSON/NDJSON 正文不聚合做签名，正文完整性依赖 HTTPS。请求 ID 用于排查，涉及写操作的业务幂等由处理器负责。

平台标准命令使用 `CommandIds`，参数和结果位于 `org.jetlinks.sdk.client.command`：

| ID 常量 | 参数 → 结果 | 输出 |
| --- | --- | --- |
| `GET_SERVICE_HEALTH` | 无 → `ServiceHealth`，状态 `UP` / `DOWN` | 单值 |
| `GET_SERVICE_INFO` | 无 → `ServiceInfo`，服务名称/发布版本/描述 | 单值 |
| `GET_TENANT_RESOURCES` | 无 → `TenantResource`，资源内含指标与功能定义 | 流 |
| `READ_TENANT_USAGE` | `ReadTenantUsage` → `TenantUsage`，绝对用量快照 | 流 |
| `SAVE_TENANT_LIMITS` | `SaveTenantLimits` → `SaveTenantLimits.Result` | 单值 |

```java
commands.register(CommandIds.GET_SERVICE_HEALTH, false,
    (app, input) -> healthService.check(app)); // 实际检查，不将传输异常转成 DOWN
commands.register(CommandIds.GET_TENANT_RESOURCES, true,
    (app, input) -> resourceService.getResources(app));
commands.register(CommandIds.READ_TENANT_USAGE, true, ReadTenantUsage.class,
    (app, input) -> resourceService.readUsage(app, input.metricIds));
commands.register(CommandIds.SAVE_TENANT_LIMITS, false, SaveTenantLimits.class,
    (app, snapshot) -> quotaRepository.replaceSnapshot(app, snapshot));
```

以上服务/仓库由接入方实现。`TenantResource` 包含 `id/name/metrics/capabilities`，目录不包含当前用量或已授予额度。
资源 ID 应稳定且全局唯一；`metrics` 内的 `TenantMetric.id` 是局部 ID，查询用量与保存额度才使用完整 `resource:metric`。
类型和限制方式沿平台枚举；单位由资源业务定义。资源功能 `capabilities` 使用 `id/name`，与 HTTP 命令能力清单分开。
`usage` 为 BigDecimal，`timestamp` / `nextResetTime` 为 epoch 毫秒，`windowDurationMillis` 为有效固定 Duration。
未开通或缺失项省略，真实零正常返回；重复快照不能相加或无条件以差值扣费。

配额请求示例：

```json
{"revision":7,"limits":[{"metricId":"example:requests","limit":1000,"windowDurationMillis":86400000}],"capabilities":[{"resource":"example","capabilities":["use"]}]}
```

处理器根据可信 `app` 确定归属，原子保存完整受管快照与业务版本：新版本应用、同版不重复执行、旧版不覆盖，
返回当前已保存版本。`limits/capabilities` 均须显式提供，遗漏的旧受管项撤销，空列表撤销对应受管配置；
撤销不能变为无限，第三方自行管理的其他配置不受影响。额度与资源功能使用同一版本并原子保存。
更新绝对上限并保留已有用量：0 禁止新增、负数规范化为 -1 不限额，降额低于用量时阻止继续增长。
窗口沿平台固定 Duration 处理；同窗口调额不能清零，不能自行解释为自然月或充值。
平台业务在首次接入、额度变更和重同步时发送，同版重试必须是同一快照；超时不能判断第三方未执行。
SDK 负责类型、验签与调用边界，版本及配额持久化由业务负责。共享示例 `ExampleStore` 提供单进程 H2 实现供参考。

## 6. Spring Boot

引入 `org.jetlinks.sdk:sdk-client-spring-boot-starter:2.0.0-SNAPSHOT`，并使用应用已有的 WebFlux 或 MVC Starter。

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

Starter 基于 Spring Boot 3.5 / Java 17，自动发现配置并适配 MVC 或 WebFlux；MVC 使用 `jakarta.servlet`。核心 SDK 可独立于 Spring 使用。

完整验收方式见 [Example 配置与验证](examples.md)。
