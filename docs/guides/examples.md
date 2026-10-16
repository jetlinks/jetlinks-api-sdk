# Example 配置与验证

两个 Java 服务共用 `example-common` 的业务流程和 H2 存储，同一份 `examples/shared-ui/dist` 提供页面。未来 Python/Node.js 只需实现 `examples/contract/openapi.yaml`，不复制前端。

## 本地完整验证

从根目录执行（JDK 17+、Maven 3.6.3+、Node.js 20+）：

```sh
npm --prefix examples/shared-ui ci
npm --prefix examples/shared-ui run typecheck
npm --prefix examples/shared-ui run lint
npm --prefix examples/shared-ui run build
mvn verify
python3 tools/check-dependencies.py
node examples/smoke/local-test.mjs
```

最后一条命令启动独立 Node 模拟平台，再分别启动两个可执行 Java JAR；每个服务独立数据库和端口。通过实际 HTTP 验证两类应用、OAuth2/本地 Token、同步/响应式、登录、通知、订阅、授权事实、命令、取消和重启恢复，结束时停止进程。可用 `EXAMPLE_KINDS=plain-java` 或 `spring-boot` 缩小范围。

模拟平台独立实现签名和 Token 验证，使用合成密钥，不导入 Java SDK。控制端点仅监听本机，不能部署为公开服务。模拟目录、私钥及配置均为运行产物。

交互演示按根 README 启动模拟平台和任意 Example。可手动触发：

```sh
curl -X POST http://127.0.0.1:19090/__fixture/notify \
  -H 'Content-Type: application/json' \
  -d '{"region":"west","type":"application.created"}'
curl -X POST http://127.0.0.1:19090/__fixture/command \
  -H 'Content-Type: application/json' \
  -d '{"region":"west","commandId":"Echo","input":{"message":"hello"}}'
```

通知类型可换为 `application.credential.synced`、`application.credential.rotated`、`application.disabled`、`application.enabled`、`application.deleted`。API 事件使用 `region: api`、`type: device.online`。页面的命令记录来自平台实际入站请求，不是点击页面生成的假记录。

## 连接真实平台

复制 `example.config.json` 为 `example.local.json`，相对路径以启动工作目录为基准：

```json
{
  "host": "127.0.0.1",
  "port": 8090,
  "publicBaseUrl": "https://your-example.example.com",
  "uiDir": "examples/shared-ui/dist",
  "database": "data/java-example",
  "apiApplications": [{
    "contextId": "api-prod",
    "applicationId": "your-api-app-id",
    "apiBaseUrl": "https://platform.example.com/jetlinks",
    "accessKey": "your-key",
    "accessSecret": "your-secret"
  }],
  "templates": [{
    "platformId": "east",
    "templateId": "your-template",
    "notificationKeyId": "notify-1",
    "notificationSecret": "your-template-notification-secret",
    "receiverKeyId": "receiver-1",
    "privateKeyFile": "data/receiver-private.pem"
  }]
}
```

API 应用订阅的 HTTP 地址使用应用面板返回的 `notificationUrl`（例如 `/jetlinks/events/api-prod`）。业务模板通知地址使用 `/jetlinks/events`，能力和命令地址分别为 `/api/capabilities`、`/api/commands/1/{commandId}`；平台配置 RSA 公钥，应用保管私钥。多个区域模板可共用同一套第三方应用，每个可信平台身份配置一条模板信任记录。

`managementToken` 为 API 示例可选字段，须是有订阅管理权限的测试用户 Token。`userToken` 可放在 API 或模板配置中，仅用于示例演示“已登录平台用户发起授权”的服务端步骤；它不会发给浏览器。真实产品应接自己的平台登录过程，不应共用测试用户 Token。

流程：

1. API：选择应用 → 调用平台 → 创建/校验/启用订阅 → 触发事件 → 查看通知及投递记录。
2. 业务：平台创建实例 → 首次凭据通知 → 选择应用 → 调用/登录/授权同步 → 平台调用命令。
3. 重同步通知前后对照 `keyRevision`；轮换、启停、同 ID 重建后再调用，确认缓存随凭据/状态更新。
4. 重启服务后确认应用和 inbox 恢复。用户会话、快照、命令记录是有界进程内演示数据，不保证重启保留。

通知详情展示脱敏原文、可信身份、重复和受理状态；原始验签字节只存服务器。H2 文件保存凭据和原始通知，应限制文件权限。Example 的管理界面只用于本地/受控联调，不自带生产登录和访问控制。

## 用户访问与登录验证

自行接入按 [Java 指南第 4 节](java-sdk.md#4-用户登录与授权事实) 实现。Example 的模板 `configuration.redirectUri` 应设为 `${publicBaseUrl}/example-api/login/callback`（无尾斜线）；通知受理后启用应用、绑定访问用户，再配置该用户的测试 `userToken`。

在共享前端选择应用，打开“登录与授权”：

1. 点击“第三方发起登录”：示例保存浏览器待登录记录，向平台申请带 state/PKCE 的授权码；标准回调只需 code/state，应用从待登录记录定位。
2. 业务应用也可点击“从平台进入应用”：示例先设置待登录 Cookie，再以测试用户调用 `_redirect`，读取 `result.location` 导航；回调核对 platformId/applicationId 后兑换 Code。
3. “刷新会话”展示核验后的应用、用户和权限；重放回调、修改 state 或平台入口的应用标识应被拒绝。“退出本地会话”只清理示例会话。

两种按钮均由 Example 发起；未先发起而直接从外部平台打开的回调会被拒绝。“从平台进入应用”仅演示原始 `_redirect` 协议，待登录 Cookie 不能替代 state/PKCE 的 Code 绑定。真实产品建议按指南将平台到访统一为带 state/PKCE 的授权；Example 不提供平台登录页。登录代码由两个 Java 服务共用，见 [ExampleService](../../sdk-java/examples/example-common/src/main/java/org/jetlinks/sdk/example/ExampleService.java) 的 `login`、`callback`。`node examples/smoke/local-test.mjs` 自动验证这两种入口、PKCE、回调重放和本地退出。

## 共用前端开发

```sh
npm --prefix examples/shared-ui run dev
```

开发代理默认指向 `http://127.0.0.1:8090`；可通过 `EXAMPLE_API_URL` 设置目标。前端仅调用同源 `/example-api`，不直接调用平台或持有平台 Secret。工作台围绕应用选择、操作结果、通知详情组织，各语言按 `info.features` 声明功能。

`smoke.test.mjs` 可指向任意已启动的 Example：

```sh
EXAMPLE_URL=http://127.0.0.1:8090 node --test examples/smoke/smoke.test.mjs
```

两个 Java 服务使用本地模拟平台各通过 11 个场景，共 22 项，包含仅带 code/state 的标准授权回调、无待登录 Cookie、state/平台入口标识篡改及回调重放检查。真实平台的权限分配、网络入口和实际事件源仍需在目标环境按上述流程验收。
