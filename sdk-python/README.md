# Python SDK

Python 3.11+，同步与异步客户端共用签名、模型和精确 JSON。当前从源码安装，Web 服务依赖仅属于可选示例。

从仓库根目录运行：

```sh
python3.11 -m venv sdk-python/.venv
sdk-python/.venv/bin/pip install -e 'sdk-python[example,test]'
npm --prefix examples/shared-ui ci
npm --prefix examples/shared-ui run build
node examples/smoke/platform-fixture.mjs
# 另一个终端
sdk-python/.venv/bin/python sdk-python/examples/server.py example.local.json
```

打开 `http://127.0.0.1:8090`，按[示例说明](../docs/guides/examples.md)触发首次通知。其他项目可直接 `pip install ./sdk-python`。

```python
from contextlib import closing, aclosing
from jetlinks_sdk_client import JetLinksClient, AsyncJetLinksClient

with JetLinksClient.local(api_base_url, access_key, access_secret) as client:
    context = client.authorization_context()
    for member in client.business_application(application_id).members():
        print(member.id)
    with closing(client.stream('/your/stream')) as rows:
        print(next(rows))

async def call_platform():
    async with AsyncJetLinksClient.local(api_base_url, access_key, access_secret) as client:
        context = await client.authorization_context()
        async with aclosing(client.stream('/your/stream')) as rows:
            async for row in rows:
                print(row)
                break
```

OAuth2 使用 `.oauth2(base, key, secret)`；已有 Token 使用 `JetLinksClient(base, token)` 或异步客户端。
`request(path, method='POST', body=data, model=YourDataclass)` 返回自定义类型；分页/流也支持 `model`。
JSON 小数使用 `Decimal`，不要先转 float；大整数保留 Python int。机器 Token 缓存合并并发刷新，业务请求不自动重试。

第三方应用按以下流程接入：

1. 本地配置模板信任、通知密钥与接收私钥；`NotificationReceiver` 验证原始正文和多值 Header。
2. 存储层在单个事务中去重、调用 `apply_lifecycle` 并保存应用状态和 inbox；提交成功后返回成功。
3. 使用 `ApplicationClients` 或 `AsyncApplicationClients`，每次 `get(namespace, current_state)` 传入持久层当前状态；使用可信通知的 API 地址。
4. `CommandService.register(id, stream, handler, model=Parameters)` 注册类型化处理器，接收可信应用快照；流式处理器使用 async generator。
5. `OAuth2` / `AsyncOAuth2` 生成 state/PKCE，绑定浏览器、原子消费回调并兑换 Code；核对用户授权上下文后创建自己的会话。

业务事实、API 订阅、事件/命令常量和标准资源/用量/配额模型均可直接使用；同步高层方法返回结果/迭代器，异步高层方法返回 awaitable/异步迭代器。
`SnapshotSynchronizer` 合并异步授权快照刷新；订阅管理需要管理身份。签名、RSA OAEP SHA-256、生命周期版本语义与 Java/Node.js 一致。
通知中的应用身份使用验签上下文；命令 Header 签名不替代 HTTPS 正文保护。

[服务入口](examples/server.py)与[SQLite 存储](examples/store.py)给出完整装配、去重和配额事务；生产多副本需自己的数据库原子性。
验证：`cd sdk-python && .venv/bin/python -m pytest`；仓库根目录运行 `EXAMPLE_KINDS=python node examples/smoke/local-test.mjs`。
