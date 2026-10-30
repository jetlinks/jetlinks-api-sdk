// 由平台集成测试启动，仅从 stdin 读取合成密钥，不写出凭据。
import assert from "node:assert/strict";
import {
  BusinessApplicationCredentials,
  JetLinksClient,
} from "../dist/index.js";
let input = "";
for await (const chunk of process.stdin) input += chunk;
const config = JSON.parse(input);
const reader = new BusinessApplicationCredentials(
  config.base,
  config.keyId,
  config.privateKey,
);
try {
  const value = await reader.read(
    config.templateId,
    config.applicationId,
    config.platformId,
  );
  assert.equal(value.accessKey, config.accessKey);
  assert.equal(value.accessSecret, config.accessSecret);
  assert.equal(String(value.keyRevision), config.keyRevision);
  const client = JetLinksClient.local(
    config.base,
    value.accessKey,
    value.accessSecret,
  );
  try {
    assert.equal(
      (await client.request("/credential-test/verify")).authenticated,
      true,
    );
  } finally {
    client.close();
  }
  console.log("node credential integration passed");
} finally {
  reader.close();
}
