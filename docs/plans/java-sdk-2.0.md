# Java SDK 2.0 开发任务

状态：已实现，按用户确认使用独立本地模拟平台验收。当前基线为 Java 17 / Spring Boot 3.5，升级范围和验证见下文；未连接生产平台、未发布 Maven 制品。

## 客户端命名

目标：与服务端开发 SDK 区分。用户已确认执行；groupId 保持 `org.jetlinks.sdk`，根父 POM 改为 `sdk-client-parent`，Java 聚合为 `sdk-client`，核心及 Starter 为 `sdk-client-core`、`sdk-client-spring-boot-starter`。示例 Maven 制品统一使用 `sdk-client-example-*` 前缀，示例聚合为 `sdk-client-examples`。

影响范围为本仓库 Maven 父子引用、两个发布模块目录、Java 源码/测试的 `org.jetlinks.sdk.client` 包路径、自动配置注册、示例启动类/JAR 路径、依赖检查及说明手册。保留 `sdk-java` 语言目录、客户端类名、`jetlinks.sdk` 配置前缀和通信契约；不新增兼容包或行为改动。

实施顺序：统一坐标及目录 → 迁移包名和注册资源 → 同步示例、脚本及指南 → 集中验证 Java 17 完整构建、自动配置发现、核心依赖边界、格式/残留引用和两个真实服务的本地协议闭环。主要风险是遗漏字符串形式的类名或旧产物路径；2.0 尚未发布，直接采用新命名。

实施及验证：8 个 Maven 制品已统一命名，83 个 Java 源码/测试迁移到 `org.jetlinks.sdk.client` 及子包；两个发布模块目录、自动配置 imports、示例 mainClass/JAR、依赖脚本和指南同步更新。Java 17 `mvn clean verify` 通过 50 项测试，0 失败/错误/跳过；以新 JAR 启动两个服务，共通过 22 个本地模拟平台场景。发布 JAR 包路径/Java 17 字节码、自动配置发现、核心依赖边界、Java 格式、文档链接和旧引用检查通过；Java 差异仅为命名、注释和格式，共享前端未变。日志为 `/tmp/jetlinks-sdk-client-rename-build.log`、`/tmp/jetlinks-sdk-client-rename-e2e.log`、`/tmp/jetlinks-sdk-client-rename-dependencies.log`；沿用 [PR #15](https://github.com/jetlinks/jetlinks-api-sdk/pull/15)。

## Java 17 / Spring Boot 3 升级

目标：整个 Java SDK、Starter 和示例统一使用 Java 17，Starter 使用 Spring Boot 3.5.16。范围为 Maven 编译/依赖、Jakarta Servlet 适配、自动配置注册、示例、CI 和接入指南；协议、业务模型、缓存、并发和共享前端不变。

1. 编译 release 改为 17；使用 Boot 3.5.16，Jackson/JUnit 对齐其 BOM，保留已对齐的 Reactor 和现有 Netty 补丁版本。
2. Servlet API 迁移为 Jakarta，使用 Boot 3 的自动配置注解及 imports 注册，移除旧 factories 注册；更新 Spring 6 已移除的 Web API。
3. 更新当前基线说明和 CI，仅承诺 Java 17 / Boot 3；下文历史 Java 8/Boot 2 验证保留为阶段记录。
4. 集中验证 Java 17 完整构建、自动配置发现、MVC/WebFlux 通知和命令、核心依赖边界及两个真实 Example 的本地模拟平台闭环。

升级风险集中在 Servlet 包名、自动配置发现及 Spring/Jackson 依赖一致性。SDK 2.0 尚未发布，直接采用新基线，不新增 Java 8/Boot 2 兼容层。

实施结果：根 `pom.xml` 统一 release=17、Boot 3.5.16、Jackson 2.21.4、JUnit 5.12.2，Reactor BOM 2024.0.18 与 Boot 对齐，保留 Netty 4.1.138 补丁版本。`JetLinksAutoConfiguration` 使用 `@AutoConfiguration` 和现有 imports 文件，移除 factories 注册；`ReceiverConfiguration`、MVC 端点及 `ServletBodies` 使用 Jakarta Servlet，`SpringBootExample` 使用 Spring 6 的 HTTP API。CI 和接入/迁移指南同步更新。

2026-10-16 验证：Temurin Java 17 `mvn clean verify` 通过 50 项测试，0 失败/错误/跳过；新增自动配置发现测试，MVC/WebFlux 通知与命令回归全部通过。core/Starter 产物 class version 为 61，Boot 示例实际依赖 Boot 3.5.16 / Spring 6.2.19。Java 17 启动两个可执行示例，通过独立 Node 模拟平台的 22 个端到端场景，包含重启恢复；核心依赖边界、Java 格式、文档链接及空白检查通过。前端代码与构建配置未变，复用此前前端验证；未连接真实平台。

日志：`/tmp/jetlinks-sdk-boot3-java17.log`、`/tmp/jetlinks-sdk-boot3-e2e.log`、`/tmp/jetlinks-sdk-boot3-dependencies.log`。沿用 [PR #15](https://github.com/jetlinks/jetlinks-api-sdk/pull/15) 交付。

## 1. 目标与边界

在 `jetlinks/jetlinks-api-sdk` 的 `2.0` 分支交付独立 Java 17 SDK，使接入方能够：

1. 使用 API 应用或第三方业务应用凭据调用平台，选择 OAuth2、本地签名机器 Token 或已有 Bearer Token。
2. 通过同步 `JetLinksClient`、响应式 `JetLinksReactiveClient` 完成同一套平台调用。
3. 接收、验签并可靠受理应用通知，处理第三方应用凭据、多区域、登录和授权事实同步。
4. 对平台提供第三方命令能力声明与执行接口，支持自定义命令、JSON 和 NDJSON。
5. 启动纯 Java 或 Spring Boot 真实服务，通过统一前端展示接入流程、通知数据和执行结果，验证完整功能。

本期以 Java 为交付主体；`sdk-python`、`sdk-nodejs` 保留语言目录及协议参考入口，不扩展为三个完整 SDK 同时开发。
不生成全平台业务接口和 DTO，不复制平台权限引擎，不提供数据库框架，不建设区域发现服务。
旧版 `ApiClient` 和 hsweb DSL 不做源码兼容层，提供 1.x → 2.0 迁移说明。

任务级别为 L，owning repository 为本仓库；新增前端仅属于 SDK Example，不修改平台后端及其运营端/运行时前端。

## 2. 仓库与模块

```text
pom.xml                              # 聚合及统一版本
sdk-java/
  pom.xml
  sdk-client-core/                          # 两个客户端、认证、通知、命令及独立模型
  sdk-client-spring-boot-starter/            # Spring 配置、Bean 和可选 Web 端点
  examples/
    example-common/                  # Java 示例流程、存储及统一接口实现
    plain-java/                      # Reactor Netty 真实服务、简短调用示例
    spring-boot/                     # Starter 真实服务，复用 example-common
sdk-python/README.md                 # 后续 examples 实现相同契约，不复制前端
sdk-nodejs/README.md                 # 后续 examples 实现相同契约，不复制前端
examples/
  shared-ui/                         # 各语言、各启动方式共用的唯一前端
  contract/openapi.yaml              # Example 接口契约，不是平台接口定义
  smoke/                             # 指向任意 Example 服务的公共验收用例
docs/
  guides/                            # 接入指南、迁移说明
  plans/                             # 本计划及索引
```

依赖规则：

- `sdk-client-core` 生产依赖限于 JDK 17、Reactor Core/Netty、Jackson 等必要通用库；不依赖 Spring、JetLinks 平台或 hsweb。
- 不引入 Lombok、Apache HttpClient 或额外 HTTP 框架；测试和示例依赖与核心隔离。
- Java 发布坐标继续使用 `org.jetlinks.sdk`，子模块分别为 `sdk-client-core`、`sdk-client-spring-boot-starter`，初始版本 `2.0.0-SNAPSHOT`。
- 统一采用 Java 17 编译和运行基线，固定 Reactor/Netty/Jackson 版本并检查 Starter 的 BOM 一致性。
- Starter 使用 Spring Boot 3.5 / Java 17，MVC 适配 Jakarta Servlet，WebFlux 适配 Spring 6；保持单一实现。
- `example-common` 和真实服务仅为示例模块，不作为核心传递依赖；示例数据库、Web 适配和前端构建均不进入 `sdk-client-core`。

## 3. 公共 API 设计约束

两个客户端共用请求模型、认证、连接池和协议实现；同步端只在最外层做有超时的阻塞转换。
业务入口为普通客户端方法或轻量 facade，不为 API 应用、业务应用各复制一套 HTTP 客户端。

| 入口 | 职责 |
| --- | --- |
| `JetLinksReactiveClient` | 通用请求、泛型 JSON、分页、`Mono<T>` 单结果和 `Flux<T>` 流式结果 |
| `JetLinksClient` | 同等单结果和分页调用；流式结果提供可关闭的 `Stream<T>` |
| 客户端认证配置 | OAuth2 client_credentials、本地签名机器 Token、外部 Bearer；授权码兑换作为明确操作 |
| 通知接收接口 | 原始请求验签，返回可信身份、事件及可持久受理的结果 |
| 命令注册接口 | 根据可信应用身份返回能力，绑定命令 ID、参数和 Mono/Flux 处理器 |

请求模型支持方法、相对路径、query、header、JSON/表单及原始内容；泛型类型由独立 `TypeRef<T>` 表达。
普通平台响应、OAuth2 原生响应和 NDJSON 分开解码，保留 HTTP 状态、平台错误码和请求关联信息。
调用方可透传未知 JSON 字段，不为每个业务模型增加 SDK 类型。

默认客户端绑定一个可信服务地址和认证上下文；业务服务按 `(platformId, applicationId)` 查询应用记录后取得相应客户端。
缓存不得跨地址、应用、凭据版本、认证用途或 Scope 混用；API 应用没有 platformId，用客户端绑定的服务地址和应用身份隔离。
Scope 未传、显式空集合和非空集合必须保持不同语义。

### 统一 Example 的复用方式

```text
examples/shared-ui（唯一前端）
                │ 同源 /example-api/*
                ├── Java：example-common + Netty / Spring Boot 适配
                ├── Python：后续实现同一 Example 接口
                └── Node.js：后续实现同一 Example 接口
                                │ 各语言 SDK
                                └── 真实 JetLinks 平台

平台 ── 通知 / OAuth2 回调 / 命令调用 ──> Example 服务
```

- 前端采用独立 Vue 3 + TypeScript + Vite 工程，使用通用组件，不依赖 JetLinks 平台前端包；接入流程和协议操作均通过后端 SDK 执行。
- 同一份前端产物由任意语言 Example 服务托管。开发时通过 Vite 代理 `/example-api`，构建时复用同一产物路径或打包步骤；语言目录不复制前端源码，也不按语言编写页面分支。
- 各服务实现 `examples/contract/openapi.yaml`，统一字段、分页、错误、会话和可用功能说明。前端只配置 Example 服务地址，不直接访问平台或持有平台 Token/Secret。
- Java 的两种服务复用 `example-common` 中的调用流程、状态归并、持久受理及接口逻辑，只分别适配 HTTP 启动、原始请求和资源释放；不在 Example 重写 SDK 签名、缓存或认证。
- Java 真实示例采用仅属于 Example 的 H2 文件数据库，通过 J05 扩展点原子保存应用绑定、凭据状态和 inbox；WebFlux/Netty 中的 JDBC 在阻塞专用线程执行，避免阻塞事件循环。数据库版本须兼容 Java 17，数据文件不提交。
- 正常启动即连接真实平台；未配置或平台不可达时明确展示状态。离线替身仅用于自动化测试，不用模拟结果冒充真实联调成功。

统一前端围绕“选择应用→执行接入操作→查看结果/通知”组织，至少覆盖：

| 功能区 | 展示和操作 |
| --- | --- |
| 应用与调用 | API/业务应用、平台/应用 ID、可信 API 地址、启停和凭据版本；选择同步/响应式及 OAuth2/本地签名方式，展示请求耗时、状态和结果 |
| 通知 | 按应用、事件类型、eventId、状态查询；查看接收时间、原始正文、解析 JSON、签名身份头、验签/持久受理/处理结果、重复或迟到原因 |
| 登录与授权 | 发起授权、查看本地会话及授权上下文；查询成员、角色、功能/菜单和资产策略，展示同步状态及最近成功快照 |
| 订阅与命令 | 订阅创建/校验/启停、任务和尝试报文；显示能力清单及平台真实命令调用、JSON/NDJSON 结果和取消状态 |

敏感凭据只在服务端加载和使用，前端仅展示标识、版本、有效期及缓存命中/刷新次数；原始报文在受控存储保留，页面/查询接口按字段脱敏，不能修改验签使用的原始字节。
验签失败的接收尝试可用于排查，但标为未可信，不进入业务 inbox、不更新应用状态；诊断数据有容量/保留期限限制。

## 4. 实施任务

J00—J10 已完成实现与本地验收。核心协议、双客户端、通知持久受理、两种服务和统一前端已落地，验证结果见文末。

### J00：多模块与依赖基线

- 状态：`2.0` 分支及独立 Maven 多模块已完成；无前置任务。
- 将旧 `src` 和构建调整为第 2 节结构，更新 Maven 聚合、发布配置及忽略规则；旧协议行为经甄别后迁移，不原样搬入平台依赖。
- 固定 Java 17 兼容且与 Boot 3.5 对齐的 Reactor/Netty/Jackson 版本；添加编译 API 兼容检查、依赖边界检查和独立构建入口。
- 验收：仅拉取本仓库即可构建；核心依赖树无 Spring、平台或 hsweb，核心及公开 API 不引用这些类型；语言空目录通过 README 纳入版本管理。

### J01：HTTP、独立模型和双客户端

- 前置：J00。
- 实现请求、泛型类型、响应和错误模型，以及独立查询条件/分页模型；覆盖常用条件组合、排序及原始 terms 透传，不重造完整 hsweb DSL。
- 使用 Reactor Netty 实现连接池、连接/响应超时、请求取消、JSON/表单/原始内容及 NDJSON 解码。URL 拼接保留 API 网关前缀，路径段与 query 分别编码，不向跨源重定向转发认证信息。
- 实现两个客户端、资源所有权、`close()`；外部注入资源由调用方管理，客户端只释放自身创建的资源。
- 限制聚合正文、NDJSON 单条大小、连接数及等待队列；同步流使用有限预取，关闭或取消时释放网络资源。
- 验收：同步/响应式返回同等结果；嵌套泛型、错误及分页正确；事件循环上的同步调用明确失败；慢流、提前关闭、取消和连接复用无泄漏。

### J02：认证、Token 缓存与并发控制

- 前置：J01。
- 支持 `/oauth2/token` 的 client_credentials，客户端认证可选 Basic 或 Post，不能同时发送；支持外部 Bearer Token。
- 实现本地签名机器 Token：按平台当前二进制格式、Base62 和 HMAC-SHA256 生成 `at_` Token，不实现为 JWT，不携带自定义用户或租户身份。
- 机器 Token 按过期时间提前刷新，同一缓存键的并发刷新共享一次执行；缓存有容量和失效边界，失败清除在途状态并限制连续刷新频率。
- 本地签名 Token 的有效期跟随平台配置，默认 2 分钟，预留时钟偏差；凭据、地址和应用状态变化时淘汰缓存及旧客户端句柄。
- 不缓存授权码；不在用户 Token 失效时降级为机器身份；不因 401 自动重放业务写请求。Token 接口失败也不形成无限重试。
- 验收：两类应用的 OAuth2 和本地 Token 均能调用平台；Scope 三种语义正确；并发请求只触发一次申请/生成；轮换和状态切换不复用旧缓存；日志无 Secret/Token。

### J03：授权码登录与当前授权上下文

- 前置：J02。
- 提供授权请求构造、state/PKCE S256 生成与校验辅助、Code 兑换和 `/authorize/context` 查询。
- 对齐平台授权接口返回 JSON `result` 地址的行为；兑换使用原始实际 redirect_uri，不包含平台追加的 code 等参数。
- 支持从平台进入第三方和第三方主动发起授权。平台 `_redirect` 不自动建立 state/PKCE，SDK 和示例明确浏览器会话关联责任。
- 回调中的 platformId/applicationId 仅用于定位已验签建立的本地应用记录；地址和凭据从该记录取得。核对授权上下文应用及主体，再由应用建立本地会话。
- 验收：state 只能消费一次，PKCE 成功及失败路径可复现；Code 兑换超时不自动重试；平台不提供 Refresh Token，过期用户授权明确重新发起。

### J04：统一通知验签和可信身份

- 前置：J01；可与 J02/J03 分开实现。
- 实现 LP 长度前缀编码、原始正文 SHA256、HMAC-SHA256、Base64URL 及恒定时间比较；签名头保留原始多值信息，检查缺失、重复和时间窗口。
- 按受信通道配置选择 API/业务通知契约，按 Header 身份和 keyId 查受信密钥；不从事件内容取得验签密钥，不按 type 或 `data.application` 猜测通道。
- API 通知不发送 `X-Platform-Id`，canonical 使用空字符串；业务通知必须携带平台身份。验签成功后核对事件 ID 和业务应用信封归属。
- 输出可信通知上下文，应用身份来自验签 Header，subject 保留事件主体含义；保留未知事件和 payload，支持业务自定义类型。
- 验收：与平台/Node 固定向量一致；篡改正文、身份、时间、重复 Header 和密钥跨应用复用均失败；API 普通 data 含 application 字段不会被误识别。

### J05：可靠受理、生命周期与多区域应用记录

- 前置：J02、J04。
- 提供一个原子受理/存储扩展点，覆盖 inbox 去重、首次应用模板绑定及状态更新。SDK 提供纯状态归并逻辑，Example 提供 H2 事务实现；生产数据库事务由接入方实现。
- 去重键采用业务 `(platformId, applicationId, eventId)`；API 记录按可信服务/应用通道隔离。已有应用归属冲突时拒绝覆盖，不能退回其他模板密钥重新绑定。
- 使用 RSA-OAEP-SHA256 且显式 MGF1-SHA256 解密凭据；支持 PEM PKCS#8 私钥和 SPKI 公钥，区分 RSA 接收密钥、通知密钥和应用访问密钥。
- 处理 created、updated、enabled、disabled、deleted、credential.rotated、credential.synced；三种凭据事件共用平台信封。存储可信 apiBaseUrl 并保留网关前缀。
- 按 occurredAt 维护创建、最新生命周期及删除边界，按 keyRevision 防止同一生命周期凭据降级；支持同 ID 重建后 revision 从 1 开始。旧通知不能覆盖新地址或删除重建记录。
- 停用、删除、轮换和地址变化使相应客户端/缓存失效；普通保存或 credential.synced 不制造新 Key/revision。
- 验收：持久受理成功后才返回成功 ACK，重复持久事件返回成功；持久化失败可触发平台重试；内存实现明确只用于演示。多区域同 ID、重复乱序、删除后迟到、重建和凭据恢复均有独立用例。

### J06：业务成员、角色及授权事实读取

- 前置：J02、J05。
- 封装成员、角色、用户角色、API Group、角色功能/菜单、资产策略读取，分页从 0 开始；接口清单见第 5 节。
- 提供逐页读取及完整快照辅助；通知只触发重读，不把事件当作完整授权事实。不在 SDK 内计算平台有效权限。
- 同一应用同步合并连续触发和在途加载，配置有限并发及失败冷却；一次同步成功后再替换快照，失败保留上次完整结果。
- 启动、失联恢复及定期同步由示例演示调度；核心只提供调用及合并机制，不增加调度框架。
- 验收：多页、不足一页、空结果、失败中断及权限拒绝正确；失败不会清空关系或被当作删除；同步缓存和在途数量有界，应用间不串数据。

### J07：API 应用订阅与投递查询

- 前置：J02、J04、J05。
- 封装订阅查询、详情、创建、全量更新、启用、停用、删除、校验及事件/渠道目录；新建订阅保持平台停用草稿语义。
- 封装任务、投递记录和尝试原始正文查询；真实路由以平台控制器为准，不臆造重试或管理端点。
- 使用 J04/J05 的通知接收流程；API 通知使用 Client ID/Secret，业务通知使用模板独立通知密钥，示例清楚区分。
- 所有管理方法使用调用方显式授权的客户端，不假设普通应用机器 Token 具有订阅管理权限，不自动创建/启用订阅。
- 验收：有管理权限时完成创建→校验→启用→接收→查记录→停用/删除；无权限时保留平台拒绝信息；正文查询区分结构化 body 与原始 rawBody。

### J08：第三方命令服务

- 前置：J01、J05。
- 提供 `GET /api/capabilities`、`POST /api/commands/1/{commandId}` 的框架无关处理入口；可选 Reactor Netty 适配复用这些入口，不新增独立服务框架。
- 以已验签 `(platformId, applicationId)` 选择实例凭据和能力，命令注册表为唯一执行入口；支持业务自定义 ID，不以内建命令交集限制能力。
- 能力响应与注册处理器保持一致，可按应用过滤；v1 空集合合法，注册可附带独立 metadata/JSON Schema，不依赖平台模型，缺省不影响支持判断，不增加 metadata 端点。
- 实现请求验签、原始 target、最终 Header、32 位小写 hex requestId、响应签名；成功和错误响应使用本次请求的凭据快照。
- Mono 返回 JSON，Flux 返回 NDJSON，支持背压、取消和逐条空闲超时；签名沿用平台合同，正文完整性依赖 HTTPS，不为签名聚合流正文。
- 限制应用数、在途命令和输入/单条响应大小；动态能力加载如需缓存，采用有限 TTL 与并发加载合并。命令执行结果不缓存、不合并、不自动重试；副作用幂等由业务实现。
- 验收：自定义命令、合法空能力、未知命令、多区域身份及轮换中在途请求正确；错误可验签，取消释放名额，慢流不无限缓冲。

### J09：Spring Boot Starter

- 前置：J03、J05、J06、J07、J08。
- 使用 `jetlinks.sdk` 配置前缀装配两个客户端、认证配置、通知受理和命令处理；公开 Bean 可覆盖，核心协议与 Starter 无循环依赖。
- 单应用通过配置直接接入；多应用/多区域服务由受信模板配置和 J05 存储查找实例，不要求把每个实例写进配置，也不新增区域配置文件。
- MVC/WebFlux 按现有应用类型条件装配，依赖可选，不强制调用方切换 Web 栈或使用独立端口；两个适配器复用同一验签和受理逻辑。
- 通知端点在明确配置受信密钥和受理实现后才启用；命令端点按需启用。WebFlux 不允许执行阻塞业务处理器，MVC 示例也不在响应式事件线程阻塞。
- 端点保留验签需要的 raw body、多值 Header 和原始 URI；超时、容量、并发、端点路径均有合理默认值及配置说明。
- 验收：纯客户端、MVC、WebFlux 三种启动场景通过；未配置时不会暴露无校验端点；只引入 core 可完全脱离 Spring 运行；Boot 3 自动配置发现及 Jakarta 适配验证通过。

### J10：真实 Example 服务、统一前端与接入指南

本任务拆为四项，前端和语言无关契约可在阶段 A/B 前置推进，服务实现随相应 SDK 能力接入，不等全部功能完成才开始联调。

**J10-A：共享接口契约与唯一前端**

- 前置：J00，结合 J01 的请求/错误模型；先定义契约再开发界面和语言适配。
- 建立 `examples/contract/openapi.yaml` 及 `examples/shared-ui`；接口描述 Example 工作流，不将平台接口逐个复制成第二套 SDK。
- 使用一个应用上下文 ID 选择已配置/已验签登记的应用，API 应用的多服务地址和业务应用的复合身份都由后端解析。浏览器不传可覆盖平台目标的任意 URL。
- 统一接口至少包含下表；SDK 实际调用仍在后端，不能让前端自行生成签名或兑换 Code。

| 相对 `/example-api` 的接口组 | 作用 |
| --- | --- |
| `GET /info`、`GET /applications`、`GET /applications/{contextId}` | 服务语言/SDK 版本、已实现功能、应用及公开接入状态 |
| `POST /applications/{contextId}/requests` | 受信平台内的通用调用，支持同步/响应式选择；流式调用返回 NDJSON |
| `GET /notifications`、`GET /notifications/{recordId}` | 通知分页列表、原始报文展示、验签和处理结果 |
| `/applications/{contextId}/login`、`/login/callback`、`/session` | 发起登录、后端兑换/建立会话、查询会话及本地退出 |
| `/applications/{contextId}/authorization` | 关系查询、显式同步及最近完整快照 |
| `/applications/{contextId}/subscriptions` | 订阅操作及投递查询，复用 SDK 而非前端直接调用平台 |
| `/applications/{contextId}/commands` | 能力和调用记录，区分平台入站调用与本地诊断 |
| `/applications/{contextId}/diagnostics` | Token 缓存/刷新、同步合并、在途及错误等脱敏状态 |

- 通知入口和命令入口沿用 SDK/平台契约，独立于上述界面查询接口；准确的方法、状态码及响应字段在 OpenAPI 中一次定义。
- UI 根据 `/info` 显示后端语言、版本和可用功能；未实现的能力明确不可用，不能显示为空数据或成功。不添加语言专属前端 API 分支。
- 通过定量分页/手动刷新或有界轮询更新页面；保留业务 eventId、requestId 和时间关联，不另建推送/日志平台。
- 验收：只修改后端地址即可使用同一前端连接两种 Java 服务；前端不含平台 SDK/验签代码或凭据，同一套接口用例可复用至后续语言。

**J10-B：Java 可独立启动的真实服务**

- 前置：J03、J05—J09、J10-A；相应功能完成后逐项接入。
- 实现 `example-common`、纯 Netty 服务和 Spring Boot 服务；两种启动方式都提供统一前端、Example API、通知/登录回调和命令端点，不只提供 main 方法打印结果。
- 使用同一示例配置模型加载服务地址、API 凭据、受信模板/RSA 和可选管理凭据；提供脱敏模板及可复制启动命令。普通应用操作与管理员操作区分权限上下文。
- API 流程：配置→OAuth2/自签调用→订阅创建/校验/启用→真实事件投递→通知验签受理→查任务/尝试→停用/删除。
- 业务流程：首次凭据通知→多区域记录→后台调用→两种登录入口→授权事实同步→提供自定义命令和 JSON/NDJSON 结果。
- H2 文件存储实现 inbox/归属/状态的原子受理，并保留通知记录和接入状态供 UI 查询；重启后恢复应用和幂等记录，不将进程内去重当作持久验证。
- 生命周期覆盖停用、轮换、删除、同 ID 重建及 credential.synced；页面可对照凭据版本、地址和缓存状态，验证同步不轮换、跨区域不串应用。
- 命令记录来自 SDK 真实处理入口，通用调用记录来自 SDK 实际请求；认证、验签、并发和缓存一律复用 SDK。前端关闭流式查看时取消后端订阅并释放资源。
- 验收：两种服务均可独立启动连接真实平台；页面可看到真实请求和通知；持久受理后 ACK、重复去重及服务重启恢复均可复现。

**J10-C：公共场景验收与真实平台联调**

- 前置：J10-A、J10-B。
- 建立 `examples/smoke`，统一使用 Example 地址作为参数，复用相同 OpenAPI 契约检查、浏览器流程和场景断言；以后切换语言后端不复制测试。
- 提供真实平台联调配置/步骤，与离线可控服务测试分开报告；涉及管理员动作显式使用有权限的测试身份，不提供绕过权限的调试通道。
- 场景覆盖两类应用、两种机器认证、同步/响应式调用、用户登录、真实通知、生命周期、权限同步、订阅和命令；额外覆盖通知重投/乱序、停机恢复、缓存并发及流取消。
- 场景结果以实际 HTTP、受理记录和最终状态为依据；平台未提供的触发条件记为待验证，不能用 UI 截图代替功能闭环。
- 验收：同一前端及公共验收套件分别对 Netty、Spring Boot 服务通过；真实平台触发通知和命令后，在界面和服务记录中找到一致的关联 ID。

**J10-D：精简指南与迁移说明**

- 前置：J10-A—J10-C。
- 指南说明准备、正常流程、启动命令、公开回调配置和逐项验收操作；配最短 SDK 调用片段，不用 Example 管理 API 代替 SDK 使用说明。
- 根 README 提供“启动 Example→打开界面→配置平台→验证接入”的入口；单独说明前端一次构建、各服务复用同一产物和以后新增语言只实现契约的步骤。
- 算法细节及线协议测试向量放参考页；提供 1.x 迁移示例和两类应用功能覆盖表。应用身份不从 event.data/subject 取，API 地址不从浏览器回调取。
- 验收：照文档可独立启动和联调，所有代码与实际方法签名一致；共享目录不存在按语言复制的页面，Python/Node 页面明确本期状态。

### J11：阶段验收、CI 与交付

- 前置：J00—J10。
- 建立 Java 17 构建、核心依赖检查、Starter 场景、前端检查及 Example 服务公共验收的 CI；设置可复现的构建/验证命令和发布说明。
- 固定平台提交与协议向量来源，进行真实 HTTP 联调；共享一套测试向量不等于仅测试 SDK 自身签名再验签。
- 集中执行第 7 节矩阵，修复后只重跑受影响阶段，最终整理版本兼容、剩余风险及平台部署前提。
- 验收：从干净仓库执行构建/测试通过，Java 17 实际运行通过；完整示例与当前平台闭环通过；无敏感测试数据，文档、产物和发布坐标一致。
- 完成后按用户授权提交/推送 `2.0` 分支并提交 PR；发布到 Maven 仓库单独按发布权限执行，不将计划确认视为生产发布授权。

## 5. 当前平台契约核对清单

### 认证与业务读取

| 功能 | 当前入口/要求 |
| --- | --- |
| 机器 Token / Code 兑换 | `POST /oauth2/token`，原生 OAuth2 响应，不包 result |
| 主动授权 | `GET /oauth2/authorize`，成功读取 JSON result 地址 |
| 从平台进入业务应用 | `GET /business-application/{id}/_redirect`，读取 result.location |
| 当前授权上下文 | `GET /authorize/context` |
| 用户详情 | `GET /user/detail`，使用同一用户 Token 核对主体 |
| 成员 | `POST /user/detail/business_application/{id}/_query` |
| 角色 | `POST /role/_query`，查询条件 applicationId 等于当前应用 |
| 用户角色 | `POST /business-application/{id}/user-roles/_query` |
| API Group | `POST /business-application/{id}/api-grants/_query` |
| 角色功能 | `POST /business-application/{id}/roles/{roleId}/authorization/_query` |
| 角色菜单 | `POST /business-application/{id}/roles/{roleId}/menus/_query` |
| 资产策略 | `GET /role/{roleId}/asset-accesses` |
| 接入信息重同步 | `POST /business-application/{id}/credential/_sync`；管理权限，返回可靠受理，未必已送达 |

管理、轮换和重同步操作不自动执行；提供明确调用入口或通用请求示例，普通保存、更新和重同步不能轮换凭据。

### API 订阅

前缀 `/application/{applicationId}/subscriptions`：

| 操作 | 相对路由 |
| --- | --- |
| 列表 / 详情 | `POST /_query`、`GET /{id}` |
| 创建 / 全量替换 | `POST /`、`PUT /{id}` |
| 启停 / 删除 | `POST /{id}/_enable`、`POST /{id}/_disable`、`DELETE /{id}` |
| 配置校验 | `POST /_validate` |
| 事件 / 渠道目录 | `GET /events`、`GET /channels` |
| 任务 / 尝试记录 | `POST /{id}/tasks/_query`、`POST /{id}/records/_query` |
| 尝试正文 | `GET /{id}/tasks/{taskId}/attempts/{attempt}/payload` |

### 通知和命令

- 通知共同 Header：`X-Signature-Version`、`X-Application-Id`、`X-Key-Id`、`X-Timestamp`、`X-Event-Id`、`X-Signature`；业务另有 `X-Platform-Id`。
- 通知 canonical：`LP("1") || LP(applicationId) || LP(platformId) || LP(keyId) || LP(timestamp) || LP(eventId) || LP(sha256Hex(rawBody))`；LP 为 UTF-8 字节前置 4 字节大端长度，secret 使用 UTF-8 原文。
- 命令请求 canonical：`["http-command","1","request",applicationId,platformId,keyId,timestamp,requestId,method,target,contentType,accept]`。
- 命令响应 canonical：`["http-command","1","response",applicationId,platformId,keyId,timestamp,requestId,requestSignature,status,contentType]`；两者均按 LP 拼接再 HMAC，Base64URL 无 padding。
- 业务 apiBaseUrl 来自可信生命周期通知，platformId 来自验签 Header；API 通知没有 platformId，SDK 不自行补充平台 Header。
- 能力地址由模板 api.address 指定，SDK 示例使用 `/api` 前缀；平台只要求 `{address}/capabilities` 和 `{address}/commands/1/{commandId}`，前缀可配置。

## 6. 实施顺序与阶段出口

| 阶段 | 任务 | 可交付结果 | 集中验证 |
| --- | --- | --- | --- |
| A | J00—J03 | 无 Spring 的双客户端、认证、授权登录 | Java 17、依赖边界、HTTP/认证/缓存并发及协议向量 |
| B | J04—J06 | 统一通知、业务凭据、多区域及授权事实读取 | 真实通知、持久受理失败、乱序/重建及分页同步 |
| C | J07—J08 | API 订阅闭环及第三方命令服务 | 订阅投递、双向签名、NDJSON、取消及限流 |
| D | J09—J11 | Starter、两种真实服务、统一前端、指南及 PR | 公共契约、浏览器流程、持久恢复、全流程联调及干净构建 |

可分阶段提交，保持每阶段可编译、可运行；不为每次编辑启动全量构建。J10-A 在阶段 A/B 前置定义公共接口及页面方案，J10-B 随 SDK 功能逐项接入；阶段 D 集中完成公共验收。依赖共享契约先完成，再扩展适配和示例。

## 7. 验证矩阵与交付条件

| 维度 | 必须验证 |
| --- | --- |
| 独立性 | 核心无 Spring/JetLinks/hsweb 依赖及公开类型泄漏；Java 17 编译和实际运行 |
| 双客户端 | 相同请求结果、泛型/分页/错误；超时、关闭、自有/外部资源和禁止事件线程阻塞 |
| 认证 | API/业务 × OAuth2/自签；Basic/Post；Scope 三态；外部用户 Token；无自动写重放 |
| 缓存与并发 | Token 单次刷新、失败恢复、容量、应用/地址隔离、同步合并、轮换失效及名额释放 |
| 登录 | 两种入口、state、PKCE、精确 redirect_uri、Code 一次消费、应用/用户身份核对 |
| 通知 | 平台独立向量、raw body、Header 归属、API/业务通道、重复/乱序、受理失败重投 |
| 凭据与多区域 | RSA 参数、同 ID 不同区域、地址前缀、同步不轮换、迟到通知、删除/重建 |
| 权限事实 | 全量多页读取、无权限与故障不清空、成功后原子替换镜像 |
| API 订阅 | 有权管理到实际接收闭环、无权拒绝、任务/记录/rawBody 可查 |
| 命令 | 能力与执行一致、自定义/空能力、身份及响应关联、错误签名、流式取消和有限缓冲 |
| Starter/服务 | 单独 core、客户端模式、MVC、WebFlux；Netty 和 Boot 真实服务均能启动、接收回调并恢复持久状态 |
| 统一前端 | 唯一源码及产物对两种服务复用；typecheck/lint/build、页面交互和脱敏展示；无语言专属 API 分支 |
| 完整 Example | 同一公共契约及浏览器用例验证两种服务；真实平台触发与通知/命令记录关联，重投/重启及取消可复现 |

测试先采用本地可控 HTTP 服务和从平台实现导出的独立固定向量；阶段出口再对当前平台联调。
通知/命令/Token 算法必须至少有一组独立期望字节或平台生成值，避免双方共享同一错误。
真实联调前准备平台地址、API 管理/调用权限、业务模板、通知密钥、RSA 密钥及公开回调；这些为联调环境参数，不写入仓库。
若无法运行某项验证，在本文件写明待执行命令及剩余风险，不以示例编译代替真实闭环。

## 8. 风险与明确取舍

1. 平台基线为当前统一契约，包含 platformId Header 和 credential.synced；旧通知签名不做自动回退。联调前确认相应平台版本已部署。
2. Java 17 / Boot 3.5 升级需核对 Reactor、Netty、Jackson 依赖管理及 Jakarta Servlet 一致性，避免消费方出现版本冲突。
3. 多区域身份仍是平台现有二元组；两个真实独立平台若使用相同 platformId/applicationId，SDK 无法靠 URL 推断其区别，需平台模板配置正确命名空间。
4. 生产可靠性依赖接入方原子持久受理；内存缓存只能提供进程内去重。真实 Example 使用 H2 演示持久闭环，但 Starter 和 core 不为业务强制选择数据库。
5. 持续流、同步 Stream 和业务处理器必须在关闭/取消时释放资源；WebFlux 处理器只能使用非阻塞实现。
6. 本地签名 Token 仍是短时 Bearer，SDK 不宣称逐请求防重放；命令正文依赖 HTTPS，执行副作用需要业务幂等。
7. 1.x 到 2.0 是主版本 API 调整；通过迁移说明解决，不把旧框架依赖重新引回 core。

## 9. 事实来源与回填

以下为 Java 17 / Boot 3 升级前的阶段记录；当前支持范围及本次验证结果以文首升级说明为准。

仓库基线：`42ac2a5fc9ba331048658e52f5e122215091b907`（旧单模块 SDK）。

平台契约基线：authentication-manager `6c78936062e2fe25dcc07d96c3a78108fb98694b`，
[平台 PR #359](https://github.com/jetlinks-v2/authentication-manager/pull/359)。实施时先确认平台后续变动，再更新本清单。

关键来源位于 authentication-manager 仓库：

- `docs/guides/third-party-business-application-developer-guide.md`：认证、通知、登录、同步及命令接入流程。
- `docs/guides/third-party-business-application-integration.md`：平台管理和完整契约。
- `docs/examples/third-party-application/jetlinks-business-client.mjs`：机器 Token、Code、上下文及分页参考。
- `docs/examples/third-party-application/jetlinks-webhook-receiver.mjs`：原始通知验签与信任归属参考。
- `docs/examples/third-party-application/jetlinks-http-command.mjs`：命令请求/响应签名参考。
- `ApplicationEventSubscriptionController`、`ApplicationEventDeliveryController`：订阅及投递运维实际路由。

### 实现入口

| 范围 | 当前入口 |
| --- | --- |
| J00—J02 | 根及 `sdk-java` Maven 聚合；`sdk-client-core` 的两个 Client、`ApiRequest`、`Query`、`auth` |
| J03—J06 | `OAuth2`、`notification/*`、`BusinessApplications`、`SnapshotSynchronizer` |
| J07—J09 | `Subscriptions`、`command/CommandService`、`sdk-client-spring-boot-starter` |
| J10 | `example-common/ExampleService`、`ExampleStore`、Netty/Boot 启动类、`examples/shared-ui`、公共 OpenAPI |
| J11 | `.github/workflows/verify.yml`、`tools/check-dependencies.py`、`examples/smoke` |
| 使用与迁移 | `README.md`、`docs/guides/java-sdk.md`、`examples.md`、`migration.md` |

前端采用接入工作台：应用选择 → 操作/结果双栏 → 通知列表/详情。复用 Ant Design Vue，不依赖平台 UI 包；无仪表盘或语言分支。API 应用同 ID 时显示本地 contextId，业务应用显示 platformId。

### 验证方式与边界

- 实际 Zulu JDK 8u504 执行 `mvn verify` 及新增 Starter 定向测试，Temurin JDK 17 执行 `mvn test`，两者均覆盖 24 项测试，0 失败/错误。核心包含独立 Node 固定向量、实际 HTTP、非 JSON 网关错误、NDJSON Unicode/取消、机器 Token 并发刷新与失败冷却、生命周期乱序/重建、签名命令及预算容量边界。Starter 覆盖无配置、双客户端、MVC/WebFlux 通知与签名命令，以及 MVC 大正文拒绝。
- `npm run typecheck`、`npm run lint`、`npm run build` 通过；唯一前端产物由两个服务托管。当前压缩 JS 约 630 kB，构建有体积提示，不影响功能；后续可按功能拆分。
- `node examples/smoke/local-test.mjs` 对两个可执行 JAR 各运行 11 个端到端场景：两类机器认证、同步/响应式、两个 API 通道同 ID、业务双区域同 ID、通知重投/篡改、订阅管理及权限拒绝、PKCE/平台登录入口、授权事实分页、命令签名/错误/限流/取消、轮换/同步/启停/删除/重建及重启恢复。模拟平台由 Node crypto 独立实现，不调用 Java SDK。
- 使用同一前端检查调用、通知筛选/脱敏详情、授权回调和本地会话；浏览器日志检查无告警/错误。Browser CLI 被本机 Chrome 沙箱限制后，改用内置浏览器验证。
- `python3 tools/check-dependencies.py` 通过：核心运行依赖无 Spring、hsweb、平台包。POM XML、文档相对链接和 `git diff --check` 通过。

本轮按用户要求使用本地模拟平台；真实平台部署后的网络、权限和实际事件源按 `docs/guides/examples.md` 验收，不能把本轮结果视为生产联调。该阶段采用 Boot 2.7 / Java 8；现已由文首的 Java 17 / Boot 3.5 基线替代。

交付：`2.0` → `master`，[PR #15](https://github.com/jetlinks/jetlinks-api-sdk/pull/15)。SDK 实现提交为 `0b84fb0336fa8abb08e3014e8d4debb80effa73c`；Maven Central 尚未发布。

### SDK 核心审查修复

范围为 `sdk-client-core` 的生命周期、快照同步、HTTP 客户端及命令错误诊断；用户已授权复现并修复。

1. 将重建通知先于旧删除通知的顺序，以及同一刷新 Publisher 重订阅导致并行加载的场景纳入回归测试，先确认失败。
2. 按创建事件隔离生命周期凭据版本；在订阅时合并快照刷新，并让任务只清理自身的在途引用。保留失败冷却、完整快照及取消语义。
3. 提取 HTTP 发送、响应收集和错误转换方法，保持超时、资源归属、分页及 JSON/NDJSON 契约。
4. 增加命令错误观察入口，向服务端保留关联身份和原始异常，兼顾同步处理、流式错误与预算释放；线上响应保持脱敏。
5. 更新简短指南，在阶段结束统一执行 Java 8/17、核心依赖检查及两个真实 Example 的独立本地模拟平台测试。

不扩大到示例职责拆分或前端改造，不新增平台依赖、业务接口封装、调度或观测框架。纯 SDK 不引入平台 TraceHolder、MBean 和 i18n；错误观察接口供调用方接入自己的日志及追踪。

实施结果：

- `ApplicationLifecycle` 按新的创建事件重置生命周期；相同创建事件不降级，先由 synced 恢复后收到初始创建通知也不会覆盖新状态。
- `SnapshotSynchronizer` 在订阅时选择共享任务，每个任务只执行一次，成功后按任务身份清理；失败冷却 1 秒。晚订阅、重订阅、下游继续触发刷新及调用方取消均不拆分在途任务。
- `JetLinksReactiveClient` 从 226 行整理为 163 行，提取发送、收集、流解码和分页推进方法；认证、超时和连接池归属不变。
- `CommandService.onError` 提供含平台/应用/请求/命令 ID 和原始 cause 的服务端观察入口，默认使用 JDK Logger。同步执行、流式和序列化失败均可观察；观察器的运行时异常不会改变协议错误或阻止名额释放。

2026-10-16 验证：先运行生命周期/快照测试，10 项中准确复现 2 项失败（旧 revision 3 未重置、额外加载）；修复后实际 Zulu Java 8 `mvn verify`、Temurin Java 17 `mvn test` 各 36 项通过，0 失败/错误。新增 12 项用例覆盖乱序创建/删除、重复/迟到创建、刷新重订阅与订阅交接、失败冷却/取消/容量、命令原始异常/流/序列化/观察器故障。

`JAVA_HOME=<Zulu 8> node examples/smoke/local-test.mjs` 对纯 Java、Spring Boot 两个真实服务分别通过 11 个场景，共 22 项；使用独立 Node 模拟平台，包含重启恢复。核心依赖边界、文档链接、源码空白及 `git diff --check` 通过；前端未变，复用此前检查证据。

本轮日志：`/tmp/jetlinks-sdk-core-red.log`、`/tmp/jetlinks-sdk-core-fix-java8.log`、`/tmp/jetlinks-sdk-core-fix-java17.log`、`/tmp/jetlinks-sdk-core-fix-e2e.log`、`/tmp/jetlinks-sdk-core-fix-dependencies.log`。相关修复纳入 SDK 2.0 的同一 PR。

### 公共常量与类型化模型

目标：接入方通过 Java 类和常量使用平台已知契约，无需反复读取 JSON 字段。范围为 `sdk-client-core` 的通知、生命周期、能力声明、业务事实、订阅管理及两个客户端，并同步 Java 示例和接入指南。

1. 根据当前平台代码定义事件目录标识、事件类型及签名 Header 常量；事件类型保持字符串，允许自定义和后续扩展。
2. 定义通知信封、生命周期数据和凭据、命令能力、分页、业务事实和订阅相关 DTO；开放业务数据使用泛型或 `Map<String, Object>`，不复制平台实体或引入平台依赖。
3. 让固定业务接口返回 DTO，为两个客户端的请求、流和分页补齐类型化入口；命令处理支持参数类。JSON 树仅留在底层编解码、通用调试或兼容入口。
4. 生命周期合并和示例改为直接访问类型化数据。签名仍针对原始字节验证，应用身份仍取自可信 Header，不改变线上协议、缓存或并发语义。
5. 阶段完成后统一验证模型与实际协议字段、未知字段/自定义事件、泛型流/分页、生命周期回归，以及 Java 8/17 和两个 Example 的本地模拟平台闭环。

本项属于公共 API 整理，用户已确认按此范围实施。SDK 2.0 尚未发布，直接统一固定业务接口，不保留重复的 JSON 业务 API；底层通用请求保留原有 JSON 入口。不扩展平台业务能力、前端交互或语言范围。风险重点为真实字段/集合结构、泛型反序列化及原始签名字节不被重写。

主要公共类型：

| 边界 | 类型与调用形式 |
| --- | --- |
| 通知 | `StructuredEvent<T>`、`BusinessApplicationEventData<T>`、`ApplicationInfo`、`LifecyclePayload`、`EncryptedCredential`；`VerifiedNotification` 提供类型化数据转换 |
| 常量 | `EventTypes` 已知事件类型、`ProtocolHeaders` 签名头常量；目录事件的定义 ID 与 type 一致，可订阅性查询当前目录；不把动态事件实例 ID 定义成常量 |
| 通用请求 | `request(request, Class<T>)` / `TypeRef<T>`；`stream`、`pages` 对两个客户端提供对应泛型重载；`Page<T>` 表达分页 |
| 业务事实 | 成员、角色、用户角色关系、API 授权、角色权限/菜单/资产使用独立 DTO，扩展配置保留 Map |
| 订阅管理 | 配置、范围、订阅视图、事件/渠道目录、校验及投递记录 DTO；创建结果为 `String`，修改/启停/删除结果为 `Integer`，按真实平台接口建模 |
| 命令 | 能力版本声明 DTO；`register(id, stream, ParameterClass.class, handler)`；处理器直接读取参数对象 |

OAuth2 机器/授权码响应一并使用 Token DTO。示例中的通用 HTTP 调试和递归脱敏仍可使用 JSON 树，不为未知业务报文预造实体。

实施结果：

- `sdk-client-core/.../event` 定义事件信封、业务包装、应用快照、生命周期载荷和加密凭据。`VerifiedNotification.event` 提供固定信封，`data(Class/TypeRef)` 转换已知或自定义载荷；路由身份仍由验签 Header 决定，签名仍核对原始字节。未来自定义的 `application.*` 类型正常受理，不误作生命周期解析。
- `model`、`subscription` 定义业务事实、授权上下文、分页、订阅配置/目录/校验和投递 DTO；`BusinessApplications`、`Subscriptions`、`OAuth2` 的固定接口直接返回对应类型，新增字段在支持扩展的模型中保留。扩展配置使用 Map，未引入平台实体或新依赖。
- 两个客户端的请求、流及分页统一支持 `Class<T>` / `TypeRef<T>`，包括嵌套泛型；分页沿用按需拉取，并尊重服务端返回的 pageSize。命令注册支持参数类和泛型，保留可信身份覆盖；默认参数及可选 metadata 使用 Map，能力声明使用 `CommandCapabilities`。
- 公共事件类型、签名 Header、可信通道类型和已知渠道常量替换生产代码中的对应魔法值；独立协议向量继续保留文字常量作交叉校验。示例使用类型化操作请求、Token、授权上下文、快照和命令参数，公共指南与迁移说明同步更新。模拟平台目录投影及 `http-hook` 渠道 ID 对齐实际平台。

2026-10-16 验证：实际 Zulu Java 8 `mvn verify`、Temurin Java 17 `mvn test` 各 43 项通过，0 失败/错误。新增 7 项模型契约测试覆盖通知/原文、身份与严格事件 ID、生命周期字段、双客户端类型化请求/流/分页和嵌套泛型、订阅标量/目录/分页/投递、业务事实/OAuth2、类型化命令与可信参数覆盖；原有缓存、并发及生命周期回归全部通过。公共 Handler 泛型签名变更后已清理旧编译产物并完整重编译。

Java 8 启动纯 Java、Spring Boot 两个可执行服务，通过独立 Node 模拟平台的 22 个端到端场景，包含两类应用、多区域、签名通知、登录、订阅、分页、命令、取消及重启恢复。核心依赖检查、Node 语法、文档链接、空白及 `git diff --check` 通过。共享前端源码未变，复用已有 typecheck/lint/build 证据；真实平台环境仍未联调。

日志：`/tmp/jetlinks-sdk-model-java8.log`、`/tmp/jetlinks-sdk-model-java17.log`、`/tmp/jetlinks-sdk-model-e2e.log`、`/tmp/jetlinks-sdk-model-dependencies.log`。实现已提交至 [PR #15](https://github.com/jetlinks/jetlinks-api-sdk/pull/15) 供评审；交付时仅回填本文件，不重复执行已覆盖当前实现的验证，Maven 包尚未发布。

### 异常、中文注释与格式整理

按用户要求统一 SDK 自身产生的失败为 `SdkException`，通过 `Code` 区分参数、状态、鉴权、编解码、加密、容量、远端及传输错误；保留远端状态/响应和原始 cause。用户处理器或存储扩展产生的异常仍保留原样，便于诊断。调整直接创建的 Mono/Flux 错误为 Supplier 延迟创建，并同步 Starter/Example 的错误映射，保持鉴权、容量及重试语义。

范围为 Java 核心、Starter、示例与对应测试；补齐公共 API、SPI 和并发/签名/生命周期边界的中文注释，统一 Java 格式。不增加异常继承树、平台依赖、业务能力或前端改动。阶段完成后集中执行异常分类/原因保留/惰性错误和 HTTP 映射回归、Java 8/17 测试及两个真实示例的本地模拟平台验收。

实施结果：`SdkException.Code` 统一 SDK 自身错误，HTTP 传输和同步超时一并归类；JSON、加密及远端错误保留原因或响应，调用方扩展异常保留原样。Mono/Flux 直接创建的错误改为 Supplier；Starter 两种 Web 端点共用 `EndpointErrors`，鉴权拒绝为 401、正文超限为 413，通知持久化故障为可重试的 503。公共类、SPI 方法及签名、缓存代数、并发预算和乱序生命周期补齐中文注释，Java 使用四空格 AOSP 格式，8 个 POM 仅整理格式。

2026-10-16 验证：最终实现的实际 Zulu Java 8 `mvn clean verify`、Temurin Java 17 `mvn clean test` 各 49 项通过，0 失败/错误/跳过；两个 Java 8 可执行服务通过独立 Node 模拟平台的 22 个端到端场景。新增 6 项测试并扩展原用例，覆盖分类/原始原因、双客户端超时/传输错误、扩展异常保留及 MVC/WebFlux 错误映射。Java 格式、POM 前后语义一致、响应式错误 Supplier、中文类注释、依赖边界、文档链接和空白检查全部通过。前端源码及构建配置未变，复用此前前端验证；未连接真实平台。

日志：`/tmp/jetlinks-sdk-exceptions-java8.log`、`/tmp/jetlinks-sdk-exceptions-java17.log`、`/tmp/jetlinks-sdk-exceptions-e2e.log`、`/tmp/jetlinks-sdk-exceptions-dependencies.log`。沿用 [PR #15](https://github.com/jetlinks/jetlinks-api-sdk/pull/15) 交付。
