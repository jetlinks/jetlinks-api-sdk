# JetLinks Client SDK 2.0

⚠️ 适配 JetLinks 企业版、旗舰版 2.20+

Java、Node.js、Python 客户端，支持 API 应用和第三方业务应用接入，不依赖 JetLinks 平台或 hsweb。

| 语言 | 运行环境 | 接入入口 |
| --- | --- | --- |
| Java | Java 17 / Spring Boot 3.5 | [Java 指南](docs/guides/java-sdk.md) |
| Node.js | Node.js 22+，示例需 22.13+ | [Node.js 指南](sdk-nodejs/README.md) |
| Python | Python 3.11+ | [Python 指南](sdk-python/README.md) |

- 同步 `JetLinksClient`、响应式 `JetLinksReactiveClient` 共用协议与认证。
- OAuth2、本地签名机器 Token、已有 Bearer Token。
- 第三方模板 RSA 私钥证明主动获取实例凭据，关闭通知时也可接入；Java、Node.js、Python 同步/异步均支持。
- 请求级资产范围与独立客户端范围视图，按组织、租户或自定义维度选择权限内资产。
- 通知验签、持久受理扩展、多区域生命周期与凭据管理。
- 授权码登录、成员/角色/授权事实、API 应用订阅、第三方 JSON/NDJSON 命令。
- 可运行的 Netty、Spring Boot、Node.js、Python Example，共用同一个前端。

## 快速调用

```xml
<dependency>
  <groupId>org.jetlinks.sdk</groupId>
  <artifactId>sdk-client-core</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
import org.jetlinks.sdk.client.ApiRequest;
import org.jetlinks.sdk.client.JetLinksClient;
import org.jetlinks.sdk.client.model.AuthorizationContext;

try (JetLinksClient client = JetLinksClient.local(apiBaseUrl, accessKey, accessSecret)) {
    AuthorizationContext context = client.request(
        ApiRequest.get("/authorize/context"), AuthorizationContext.class);
}
```

自签 Token 的有效期须与平台配置一致，SDK 默认缓存 2 分钟。代码中的类型位于 `org.jetlinks.sdk.client` 和 `org.jetlinks.sdk.client.auth`。

Java 快照发布到 JetLinks Nexus 私服，仓库配置与 CI 操作见 [Java 快照发布](docs/guides/java-sdk.md#7-构建与快照发布)。

## 运行 Example

Java 示例需要 JDK 17+、Maven 3.6.3+；Node.js 示例需要 22.13+，Python 示例需要 3.11+。
所有示例共用的前端与模拟平台使用 Node.js。从仓库根目录执行：

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
java -jar sdk-java/examples/plain-java/target/sdk-client-example-java-2.0.0-SNAPSHOT.jar example.local.json
# 或使用 Spring Boot（两者默认端口相同，选择一个运行）
java -Dexample.config=example.local.json -jar sdk-java/examples/spring-boot/target/sdk-client-example-spring-boot-2.0.0-SNAPSHOT.jar
# 或 Node.js（先 npm --prefix sdk-nodejs ci && npm --prefix sdk-nodejs run build）
node sdk-nodejs/examples/server.mjs example.local.json
# 或 Python（先在虚拟环境 pip install -e 'sdk-python[example]'）
sdk-python/.venv/bin/python sdk-python/examples/server.py example.local.json
```

模拟首次业务应用通知：

```sh
curl -X POST http://127.0.0.1:19090/__fixture/notify \
  -H 'Content-Type: application/json' \
  -d '{"region":"east","type":"application.created"}'
```

页面可查看应用、执行请求、通知详情、登录、授权事实、订阅和命令。模拟平台返回的业务 API 前缀为 `/gateway/east` 等；可在请求面板调用 `/fixture/echo`。

安装各语言依赖后，自动启动模拟平台和四种 Example、执行同一套完整场景并停止进程：

```sh
node examples/smoke/local-test.mjs
```

连接真实平台时，复制 `example.config.json` 为本地配置，填写 API 应用或模板信任信息，按[接入指南](docs/guides/java-sdk.md)配置公开回调地址。模拟平台仅供本地测试。Example 默认监听环回地址。

## Python 协议联调工具

无需构建前端即可运行 HTTP 接收脚本，验证业务应用凭据通知或 API 订阅。它们只演示接收协议，不提供页面，不代替语言 SDK 或公共 Example 服务。

- [业务应用平台配置](docs/guides/第三方业务应用接入说明.md)
- [业务应用开发与 Python 示例](docs/guides/第三方业务应用开发指南.md)
- [API 订阅接入与 Python 示例](docs/guides/第三方订阅接入指南.md)

## 目录

| 目录 | 内容 |
| --- | --- |
| `sdk-java/sdk-client-core` | 通用协议、双客户端及扩展点 |
| `sdk-java/sdk-client-spring-boot-starter` | Boot 3.5 自动配置、Jakarta MVC/WebFlux 适配 |
| `sdk-java/examples` | Java 共用流程与两种 HTTP 启动方式 |
| `examples/shared-ui` | 所有语言 Example 共用的 Vue 前端 |
| `examples/contract/openapi.yaml` | Example 接口契约 |
| `examples/smoke` | 独立模拟平台与公共场景验收 |
| `sdk-python`、`sdk-nodejs` | 独立语言 SDK、持久化真实服务示例 |

[Java 接入指南](docs/guides/java-sdk.md) · [Example 配置与测试](docs/guides/examples.md) · [1.x 迁移](docs/guides/migration.md) · [开发与验收范围](docs/plans/java-sdk-2.0.md)

`2.0` 是不兼容的主版本；未提供旧版 `ApiClient`/hsweb DSL 兼容层。Java SNAPSHOT 由合入后的 CI 发布至 Nexus；正式制品尚未发布到 Maven Central/npm/PyPI，请按语言指南安装。
