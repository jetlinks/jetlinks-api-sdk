import {
  JetLinksClient,
  type AssetScopeConfigurer,
  AssetScope,
} from "../../dist/index.js";

const client = new JetLinksClient("http://localhost");
client.assetScope((s) => s.organization("A"));
client.assetScope((s) => {
  s.tenant("T");
});
const block: (s: AssetScope) => void = (s) => {
  s.organization("B");
};
client.assetScope(block);
void client.request("/echo", { assetScope: (s) => s.organization("A") });
// @ts-expect-error async 配置即使返回 Promise<void> 也必须拒绝。
client.assetScope(async (s) => {
  s.organization("A");
});
void client.request("/echo", {
  // @ts-expect-error 请求级异步回调不能通过 void 返回类型规则混入。
  assetScope: async (s) => {
    s.organization("A");
  },
});
// @ts-expect-error 显式 Promise 返回值也必须拒绝。
const invalid: AssetScopeConfigurer = () => Promise.resolve();
void invalid;
