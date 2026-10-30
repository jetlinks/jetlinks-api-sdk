# Node.js SDK

## 关闭通知时获取应用凭据

先在平台模板 `configuration.credential` 登记 `{keyId, publicKey}`，无需已有 AccessSecret 或通知签名 Secret。私钥使用 PKCS#8 PEM，保留在第三方服务端。

```js
import { BusinessApplicationCredentials, JetLinksClient } from '@jetlinks/sdk-client';
const reader = new BusinessApplicationCredentials(apiBaseUrl, rsaKeyId, privateKeyPem);
try {
  const credential = await reader.read(templateId, applicationId, platformId ?? '');
  const client = JetLinksClient.local(apiBaseUrl, credential.accessKey, credential.accessSecret);
  try { await client.authorizationContext(); }
  finally { client.close(); }
} finally { reader.close(); }
```

reader 可长期复用；每次读取生成新 nonce 和时间，执行 RSA-PSS/SHA256（MGF1-SHA256，salt=32），校验响应身份及 nonce 后用 OAEP-SHA256 解密。platformId 留空按模板 ID，显式配置时必须传配置值。应用与模板必须启用且归属匹配；可信 apiBaseUrl 保留网关前缀，生产使用 HTTPS。凭据仅在首次接入、轮换后或认证失败时有界刷新，平常复用。400 为格式/过期，401 为身份/来源/状态拒绝，429/503 可有界退避；SDK 不自动重试，不跟随重定向，不记录凭据。该只读签名在 ±60 秒内允许重试，不提供 nonce 单次消费保证。`keyRevision` 按现有精确 JSON 规则返回 number 或 Decimal。

## 资产范围

生产环境请先阅读[客户端并发与对象复用](../docs/guides/examples.md#客户端并发与对象复用)：
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

Node.js 22+，ES module，附 TypeScript 类型。当前从源码安装；示例使用 Node.js 22.13+ 的内置 SQLite。

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

打开 `http://127.0.0.1:8090`，按[示例说明](../docs/guides/examples.md)触发首次通知。前端、配置和平台模拟服务与其他语言共用。
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

[服务入口](examples/server.mjs)展示装配，[存储示例](examples/store.mjs)展示事务、去重和配额版本；SQLite 仅演示单进程。
验证：`npm --prefix sdk-nodejs test`；仓库根目录运行 `EXAMPLE_KINDS=nodejs node examples/smoke/local-test.mjs`。
