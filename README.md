# JetLinks API SDK 2.0

独立 Java 17 SDK，支持 API 应用和第三方业务应用接入。核心只依赖 Reactor Netty、Jackson 等通用库，不依赖 JetLinks 平台、hsweb 或 Spring。

- 同步 `JetLinksClient`、响应式 `JetLinksReactiveClient` 共用协议与认证。
- OAuth2、本地签名机器 Token、已有 Bearer Token。
- 通知验签、持久受理扩展、多区域生命周期与凭据管理。
- 授权码登录、成员/角色/授权事实、API 应用订阅、第三方 JSON/NDJSON 命令。
- 可运行的 Netty、Spring Boot Example，共用同一个前端。

## 快速调用

```xml
<dependency>
  <groupId>org.jetlinks.sdk</groupId>
  <artifactId>sdk-core</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
CachedTokenProvider tokens =
    OAuth2.localTokens(accessKey, accessSecret, null, Duration.ofMinutes(2));
try (JetLinksClient client = new JetLinksClient(apiBaseUrl, tokens)) {
    AuthorizationContext context = client.request(
        ApiRequest.get("/authorize/context"), AuthorizationContext.class);
}
```

自签 Token 的有效期须与平台配置一致，SDK 默认缓存 2 分钟。代码中的类型位于 `org.jetlinks.sdk` 和 `org.jetlinks.sdk.auth`。

## 运行 Example

需要 JDK 17+、Maven 3.6.3+、Node.js 20+。从仓库根目录执行：

```sh
npm --prefix examples/shared-ui ci
npm --prefix examples/shared-ui run build
mvn verify
```

先启动本地模拟平台，生成仅含合成凭据的 `example.local.json`：

```sh
node examples/smoke/platform-fixture.mjs
```

另一个终端启动 Example，再打开 http://127.0.0.1:8090：

```sh
java -jar sdk-java/examples/plain-java/target/plain-java-2.0.0-SNAPSHOT.jar example.local.json
# 或使用 Spring Boot（两者默认端口相同，选择一个运行）
java -Dexample.config=example.local.json -jar sdk-java/examples/spring-boot/target/spring-boot-2.0.0-SNAPSHOT.jar
```

模拟首次业务应用通知：

```sh
curl -X POST http://127.0.0.1:19090/__fixture/notify \
  -H 'Content-Type: application/json' \
  -d '{"region":"east","type":"application.created"}'
```

页面可查看应用、执行请求、通知详情、登录、授权事实、订阅和命令。模拟平台返回的业务 API 前缀为 `/gateway/east` 等；可在请求面板调用 `/fixture/echo`。

自动启动模拟平台和两个 Example、执行完整场景并停止进程：

```sh
node examples/smoke/local-test.mjs
```

连接真实平台时，复制 `example.config.json` 为本地配置，填写 API 应用或模板信任信息，按[接入指南](docs/guides/java-sdk.md)配置公开回调地址。模拟平台仅供本地测试。Example 默认监听环回地址。

## 目录

| 目录 | 内容 |
| --- | --- |
| `sdk-java/sdk-core` | 通用协议、双客户端及扩展点 |
| `sdk-java/sdk-spring-boot-starter` | Boot 3.5 自动配置、Jakarta MVC/WebFlux 适配 |
| `sdk-java/examples` | Java 共用流程与两种 HTTP 启动方式 |
| `examples/shared-ui` | 所有语言 Example 共用的 Vue 前端 |
| `examples/contract/openapi.yaml` | Example 接口契约 |
| `examples/smoke` | 独立模拟平台与公共场景验收 |
| `sdk-python`、`sdk-nodejs` | 后续实现入口 |

[Java 接入指南](docs/guides/java-sdk.md) · [Example 配置与测试](docs/guides/examples.md) · [1.x 迁移](docs/guides/migration.md) · [开发与验收范围](docs/plans/java-sdk-2.0.md)

`2.0` 是不兼容的主版本；未提供旧版 `ApiClient`/hsweb DSL 兼容层。当前版本尚未发布到 Maven Central。
