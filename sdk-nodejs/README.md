# Node.js SDK

本期先交付 Java 2.0。后续语言 SDK 和真实服务 Example 在本目录实现。
Example 必须实现 [公共契约](../examples/contract/openapi.yaml)，直接托管 [统一前端](../examples/shared-ui)，不得复制前端或新增语言专属页面接口。
公共验收：

```sh
EXAMPLE_URL=http://127.0.0.1:8090 node --test examples/smoke/smoke.test.mjs
```
