# Node.js SDK

## 安装

公开发布到 npmjs 后，用户无需 GitHub Token 或自定义 registry：

```sh
npm install @jetlinks/sdk-client@next
# 稳定版发布后：npm install @jetlinks/sdk-client
```

`next` 用于 alpha/beta/rc/dev 等预发布版本，`latest` 用于稳定版。SDK 要求 Node.js 22+，支持 ES module 并附 TypeScript 类型。
首次公开发布前，也可按下方说明从源码打包安装。

## 资产范围

生产环境请先阅读[客户端并发与对象复用](https://github.com/jetlinks/jetlinks-api-sdk/blob/2.0/docs/guides/examples.md#客户端并发与对象复用)：
根客户端/管理器在同一事件循环内长期复用，options/body/迭代器按操作创建，临时范围视图通常无需额外缓存。

```js
await client.request('/your/api', {
  assetScope: scope => scope.organization(orgId),
});
const scoped = client.assetScope(scope => scope
  .tenant(tenantId).scope('org', orgId));
try {
  for await (const row of scoped.pages('/your/_query')) console.log(row);
  await scoped.subscriptions(applicationId).events();
} finally { scoped.close(); }
```

范围视图共享认证、连接及并发预算，不修改原客户端；关闭视图阻止后续调用，根客户端关闭后所有视图失效。
普通请求、流、分页及业务/订阅 API 自动继承默认范围。不同类型取交集，同类型后设置覆盖前设置；
请求 DSL 覆盖请求原始 Header，二者覆盖客户端同类型默认值。空值、首尾空白、控制字符、类型含冒号、
超长值及超过 16 个类型抛出 `SdkError('INVALID_ARGUMENT', ...)`。类型最多 64、ID 最多 256 个 UTF-16 单元。
可继续通过 `headers: { 'X-Asset-Scope': 'org:' + orgId }` 指定单个范围；多维范围使用 DSL。
SDK 通过 Undici request 发送独立的重复 Header，避免 Fetch 把范围合并为逗号列表，并保留压缩响应解码。
资产范围不会改变 OAuth2 授权 Scope 或 Token 缓存键。平台负责最终权限校验，指定范围不会增加身份权限，与平台强制范围冲突时仍会拒绝。

## 从源码运行示例

示例使用 Node.js 22.13+ 的内置 SQLite。

从仓库根目录运行：

```sh
npm --prefix sdk-nodejs ci
npm --prefix sdk-nodejs run build
npm --prefix examples/shared-ui ci
npm --prefix examples/shared-ui run build
node examples/smoke/platform-fixture.mjs
# 另一个终端
node sdk-nodejs/examples/server.mjs example.local.json
```

打开 `http://127.0.0.1:8090`，按[示例说明](https://github.com/jetlinks/jetlinks-api-sdk/blob/2.0/docs/guides/examples.md)触发首次通知。前端、配置和平台模拟服务与其他语言共用。
在自己的项目中可先执行 `npm --prefix sdk-nodejs pack`，再安装生成的 tgz；无须安装示例数据库或 Web 框架依赖。

```js
import { JetLinksClient } from '@jetlinks/sdk-client';

const client = JetLinksClient.local(apiBaseUrl, accessKey, accessSecret);
try {
  const context = await client.authorizationContext();
  for await (const device of client.pages('/device-instance/_query')) console.log(device.id);
  // 提前退出循环会关闭 HTTP 流。
  for await (const row of client.stream('/your/stream')) { console.log(row); break; }
} finally { client.close(); }
```

使用 OAuth2：`JetLinksClient.oauth2(base, key, secret)`；已有 Token：`new JetLinksClient(base, {token})`。
两种机器认证均缓存 Token、合并并发刷新；客户端不重试业务请求。`request(path, {method, body, signal})` 支持自定义接口和取消。
未知小数/大整数从 JSON 解析起保留为 `Decimal`，安全整数为 `number`；计量运算使用 `new Decimal(value)`，高精度输入使用字符串构造 Decimal，输出仍为 JSON number。

第三方应用的正常流程：

1. 在本地登记模板的 `platformId/templateId`、通知密钥和接收私钥；以原始正文、多值 Header 调用 `NotificationReceiver`。
2. 持久层在同一事务去重并执行 `applyLifecycle(current, notice, privateKey)`，保存状态和 inbox，提交后返回 2xx。
3. 每次业务操作从持久层取当前状态，调用 `ApplicationClients.get(namespace, state)`；地址使用通知的 `apiBaseUrl`，缓存随凭据、生命周期和启停变化失效。
4. 注册实际业务命令：`commands.register('GetServiceInfo', false, (application, input) => result)`；流式处理器返回 AsyncIterable。
5. 用户登录调用 `OAuth2.authorization` 生成 state/PKCE，绑定浏览器并原子消费回调，再 `exchange` 获取用户 Token、核对授权上下文和建立本地会话。

`BusinessApplications` 提供成员/角色/授权事实及凭据重同步，`Subscriptions` 提供目录、订阅管理和投递查询；后者需要明确的管理权限。
`CommandIds`、`EventTypes`、`Headers` 和标准资源/用量/配额类型直接复用平台契约。通知身份来自验签上下文，不能从 `event.data` 或 `subject` 推断。
Header 签名和 RSA OAEP SHA-256 算法与 Java 一致；命令正文完整性依赖 HTTPS。

[服务入口](https://github.com/jetlinks/jetlinks-api-sdk/blob/2.0/sdk-nodejs/examples/server.mjs)展示装配，[存储示例](https://github.com/jetlinks/jetlinks-api-sdk/blob/2.0/sdk-nodejs/examples/store.mjs)展示事务、去重和配额版本；SQLite 仅演示单进程。
验证：`npm --prefix sdk-nodejs test`；仓库根目录运行 `EXAMPLE_KINDS=nodejs node examples/smoke/local-test.mjs`。

## 维护者：发布到 npmjs

`.github/workflows/npm-publish.yml` 由 `node-v<version>` Tag 推送触发。Tag 必须与 `package.json` 中的规范 SemVer 完全一致，
例如 `node-v2.0.0-alpha.1`；不接受分支、不同版本或 `+build` 元数据（npm 不用它区分版本）。
预发布版本使用 `next`，稳定版使用 `latest`。已发布版本不能覆盖，每次发布都要更新 `package.json` 和 `package-lock.json`。
先通过 PR 提交版本变化，再推送对应 Tag；工作流不会自动修改版本或创建 Tag。

构建任务在 Node.js 24 上执行 lint、TypeScript 构建和测试并生成 tgz；独立发布任务只上传该 tgz，禁用生命周期脚本，
通过 OIDC 可信发布并生成 provenance，不配置长期 `NPM_TOKEN`。发布串行执行，避免并发更新 `next` / `latest`。

### 首次启用

1. npm 包负责人确认拥有 npmjs 的 `@jetlinks` scope 发布权限。包尚不存在时，先在本地使用自己的 npm 登录完成一次发布：

   ```sh
   cd sdk-nodejs
   npm ci
   npm run lint && npm test
   npm pack
   npm login --registry=https://registry.npmjs.org/
   npm publish ./jetlinks-sdk-client-2.0.0-alpha.0.tgz --access public --tag next --ignore-scripts
   ```

   文件名须对应实际版本；凭据只保存在发布者本机，不提交 `.npmrc` 或 Token。
2. 在 npmjs 的包 **Settings → Trusted Publisher** 中选择 GitHub Actions，填写组织 `jetlinks`、仓库 `jetlinks-api-sdk`、
   工作流文件名 `npm-publish.yml`（不含目录），Environment 留空，并允许直接 `npm publish`。
   按 npm 当前规则，新配置须在两天内完成首次成功发布验证，过期后需重新配置。
3. 合入工作流和新的包版本后，推送对应 Tag。首次本地发布的版本已被占用，首次 OIDC 发布须使用下一个版本。

OIDC 需要 GitHub 托管 runner、Node.js 22.14+ 和 npm 11.5.1+；工作流使用 Node.js 24 和 npm 11。
完整配置见 [npm Trusted Publishing](https://docs.npmjs.com/trusted-publishers/)。

手动重试必须选择原 Tag，保证构建内容与 provenance 的提交一致；在仓库默认分支已有该工作流后运行：

```sh
gh workflow run npm-publish.yml --ref node-v2.0.0-alpha.1
```

在分支上手动运行会被版本校验拒绝；若该版本已经发布，npm 会拒绝重复发布，需更新版本后重新走发布流程。
