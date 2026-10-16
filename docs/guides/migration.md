# 从 1.x 迁移

2.0 是新的主版本，不保留旧 `ApiClient`、`WebApiClient` 或 hsweb Query DSL 的源码兼容层。

| 1.x | 2.0 |
| --- | --- |
| 根 JAR | `sdk-java/sdk-core`，坐标 `org.jetlinks.sdk:sdk-core` |
| `WebApiClient` / `WebOAuth2Client` | `JetLinksReactiveClient`，普通线程可选 `JetLinksClient` |
| `ClientConfig` / `OAuth2Config` | 可信 API 地址 + `TokenProvider`；OAuth2 使用 `OAuth2.machineTokens` |
| 旧请求签名 Header | 平台当前 `at_` 本地机器 Token 或 OAuth2 Bearer |
| `SimpleApiRequest` / 业务请求 DTO | `ApiRequest` + SDK / 自己的 DTO，`Class<T>` 或 `TypeRef<T>` |
| `QueryPageRequest` / hsweb DSL | 独立 `Query`，也可直接传原始 terms JSON |
| Spring WebClient 为必需依赖 | core 使用 Reactor Netty，Spring 仅在可选 Starter |

迁移时先确认平台已支持当前 Token/通知/命令协议。通知身份从验签 Header 取得；业务应用使用通知提供的可信 API 地址。两个客户端都应在服务关闭时释放；同步流用 try-with-resources 关闭，响应式链路传播取消。

固定通知、生命周期、OAuth2、业务事实和订阅接口返回 Java 模型，已知事件使用 `EventTypes` 常量；JSON 树留在通用底层请求和诊断入口。自定义数据通过泛型 DTO 或 Map 扩展，无需依赖平台实体。

示例只保留一份共享前端和一份 Java 流程实现；不再把测试目录下的旧 Demo 当启动入口。使用方式见 [Java 指南](java-sdk.md) 和 [Example](examples.md)。
