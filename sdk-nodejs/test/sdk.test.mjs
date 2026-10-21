import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import crypto from "node:crypto";
import http from "node:http";
import { once } from "node:events";
import {
  sign,
  localToken,
  decode,
  encode,
  Decimal,
  CachedTokenProvider,
  NotificationVerifier,
  ApplicationClients,
  CommandService,
  JetLinksClient,
  SdkError,
  applyLifecycle,
  SnapshotSynchronizer,
} from "../dist/index.js";
const vectors = JSON.parse(
  fs.readFileSync(
    new URL("../../examples/contract/vectors.json", import.meta.url),
    "utf8",
  ),
);
const state = () => ({
  platformId: "east",
  applicationId: "app",
  templateId: "template",
  apiBaseUrl: "http://127.0.0.1:1/prefix",
  accessKey: "key",
  accessSecret: "secret",
  createdAt: 1,
  keyRevision: 1,
  enabled: true,
  updatedAt: 1,
  deletedAt: 0,
});

test("independent signature/Base62 vectors and lossless decimal wire number", () => {
  assert.equal(
    sign(vectors.signature.secret, vectors.signature.fields),
    vectors.signature.expected,
  );
  for (const v of vectors.tokens)
    assert.equal(localToken(v.key, v.secret, v.scopes, v.issuedAt), v.expected);
  const value = decode('{"usage":' + vectors.decimal + "}");
  assert.ok(value.usage instanceof Decimal);
  assert.ok(value.usage.equals(vectors.decimal));
  assert.equal(decode(encode(value)).usage.toFixed(20), vectors.decimal);
  assert.ok(!encode(value).includes('"123456'));
});
test("shared token refresh survives one caller cancellation and invalidation blocks stale cache writes", async () => {
  let resolve,
    calls = 0;
  const cache = new CachedTokenProvider(() => {
    calls++;
    return new Promise((r) => (resolve = r));
  });
  const requests = Array.from({ length: 20 }, () => cache.token());
  await Promise.resolve();
  assert.equal(calls, 1);
  cache.invalidate();
  resolve({ token: "old", expiresAt: Date.now() + 60000 });
  assert.ok((await Promise.all(requests)).every((v) => v === "old"));
  const fresh = cache.token();
  await Promise.resolve();
  assert.equal(calls, 2);
  resolve({ token: "new", expiresAt: Date.now() + 60000 });
  assert.equal(await fresh, "new");
  assert.equal(await cache.token(), "new");
});
test("failed token refresh is coalesced during cooldown", async () => {
  let count = 0;
  const cache = new CachedTokenProvider(async () => {
    count++;
    throw new Error("offline");
  });
  await assert.rejects(cache.token());
  await assert.rejects(cache.token());
  assert.equal(count, 1);
});
test("notification rejects duplicates of headers before trust lookup", async () => {
  const verifier = new NotificationVerifier(() => {
    throw new Error("resolver must not run");
  });
  await assert.rejects(
    verifier.verify({ "X-Signature-Version": ["1", "1"] }, Buffer.from("{}")),
    (e) => e.code === "AUTHENTICATION",
  );
});
test("command timeout releases its budget and aborts the handler signal", async () => {
  let signal;
  const service = new CommandService(() => state(), 1, 10);
  service.onError = () => {};
  service.register("Slow", false, (_state, _input, value) => {
    signal = value;
    return new Promise(() => {});
  });
  const stamp = String(Date.now()),
    request = "b".repeat(32),
    target = "/api/commands/1/Slow";
  const signature = sign("secret", [
    "http-command",
    "1",
    "request",
    "app",
    "east",
    "key",
    stamp,
    request,
    "POST",
    target,
    "application/json",
    "application/json",
  ]);
  const response = await service.handle(
    "POST",
    target,
    {
      "X-Signature-Version": "1",
      "X-Platform-Id": "east",
      "X-Application-Id": "app",
      "X-Key-Id": "key",
      "X-Timestamp": stamp,
      "X-Request-Id": request,
      "X-Signature": signature,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    Buffer.from("{}"),
  );
  assert.equal(response.status, 500);
  await response.text();
  assert.equal(service.inFlight, 0);
  assert.ok(signal.aborted);
});
test("lifecycle delete cannot resurrect; new instance and old revision are isolated", () => {
  const notice = {
    platformId: "east",
    applicationId: "app",
    key: { templateId: "template" },
    event: {
      type: "application.deleted",
      occurredAt: 3,
      data: { application: { createTime: 1 } },
    },
  };
  const deleted = applyLifecycle(state(), notice, () => "");
  assert.equal(deleted.accessSecret, undefined);
  assert.equal(
    applyLifecycle(
      deleted,
      {
        ...notice,
        event: { ...notice.event, type: "application.enabled", occurredAt: 4 },
      },
      () => "",
    ).enabled,
    false,
  );
  const recreated = applyLifecycle(
    deleted,
    {
      ...notice,
      event: {
        type: "application.created",
        occurredAt: 6,
        data: { application: { createTime: 5 } },
      },
    },
    () => "",
  );
  assert.equal(recreated.deletedAt, 0);
  assert.equal(recreated.createdAt, 5);
  assert.equal(recreated.keyRevision, 0);
});
test("RSA OAEP SHA256/MGF1 SHA256 interoperates with independent crypto", async () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const { decrypt } = await import("../dist/index.js");
  const raw = crypto.publicEncrypt(
    { key: publicKey, oaepHash: "sha256" },
    Buffer.from("轮换-secret"),
  );
  assert.equal(
    decrypt(
      raw.toString("base64"),
      privateKey.export({ format: "pem", type: "pkcs8" }),
    ),
    "轮换-secret",
  );
});
test("credential revisions above JS safe integers retain ordering", () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const credential = {
    accessKey: "key",
    keyId: "receiver",
    encryptedAccessSecret: crypto
      .publicEncrypt(
        { key: publicKey, oaepHash: "sha256" },
        Buffer.from("rotated"),
      )
      .toString("base64"),
  };
  const current = { ...state(), keyRevision: new Decimal("9007199254740992") };
  const notice = (revision) => ({
    platformId: current.platformId,
    applicationId: current.applicationId,
    key: { templateId: current.templateId },
    event: {
      type: "application.credential.rotated",
      occurredAt: 2,
      data: {
        application: { createTime: 1 },
        payload: {
          credential: { ...credential, keyRevision: new Decimal(revision) },
        },
      },
    },
  });
  const updated = applyLifecycle(current, notice("9007199254740993"), () =>
    privateKey.export({ format: "pem", type: "pkcs8" }),
  );
  assert.equal(updated.accessSecret, "rotated");
  assert.ok(updated.keyRevision.eq("9007199254740993"));
  assert.equal(
    applyLifecycle(updated, notice("9007199254740992"), () => {
      throw new Error("stale credential must not decrypt");
    }).accessSecret,
    "rotated",
  );
});
test("application clients reuse, rotate, bound scope/region and close", async () => {
  const cache = new ApplicationClients(2),
    current = state();
  const old = cache.get("a", current, "local");
  assert.equal(old, cache.get("a", { ...current }, "local"));
  assert.notEqual(old, cache.get("a", current, "local", []));
  assert.throws(
    () => cache.get("other", current),
    (e) => e.code === "LIMIT_EXCEEDED",
  );
  const next = cache.get(
    "a",
    { ...current, keyRevision: 2, accessSecret: "new" },
    "local",
  );
  assert.notEqual(next, old);
  await assert.rejects(old.request("/test"), (e) => e.code === "INVALID_STATE");
  assert.throws(
    () =>
      cache.get("a", {
        ...current,
        keyRevision: 2,
        accessSecret: "new",
        updatedAt: 2,
        enabled: false,
      }),
    (e) => e.code === "INVALID_ARGUMENT",
  );
  cache.close();
  assert.throws(
    () => cache.get("a", current),
    (e) => e.code === "INVALID_STATE",
  );
});
test("transport preserves base prefix and propagates HTTP error/size limit", async () => {
  const server = http.createServer((req, res) => {
    if (req.url === "/prefix/ok")
      res.end('{"status":200,"result":{"id":"ok"}}');
    else {
      res.writeHead(403);
      res.end('{"message":"forbidden"}');
    }
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const client = new JetLinksClient(
    `http://127.0.0.1:${server.address().port}/prefix`,
  );
  try {
    assert.deepEqual(await client.request("/ok"), { id: "ok" });
    await assert.rejects(
      client.request("/forbidden"),
      (e) => e.code === "REMOTE_ERROR" && e.status === 403,
    );
    await assert.rejects(
      client.request("//other"),
      (e) => e.code === "INVALID_ARGUMENT",
    );
    const tiny = new JetLinksClient(client.baseUrl, { maxBytes: 4 });
    try {
      await assert.rejects(
        tiny.request("/ok"),
        (e) => e.code === "LIMIT_EXCEEDED",
      );
    } finally {
      tiny.close();
    }
  } finally {
    client.close();
    server.closeAllConnections();
    await new Promise((r) => server.close(r));
  }
});
test("snapshot concurrency publishes only complete success and retains old snapshot", async () => {
  let count = 0;
  const sync = new SnapshotSynchronizer(async function* () {
    count++;
    yield "good";
  }, 1);
  await Promise.all([sync.refresh(), sync.refresh()]);
  assert.equal(count, 1);
  assert.deepEqual(sync.current, ["good"]);
});
test("signed command failure, trusted identity, response signature and cancellation budget", async () => {
  const s = state(),
    service = new CommandService(() => s, 1);
  service.onError = () => {};
  service
    .register("Echo", false, (_a, input) => input)
    .register("Fail", false, () => {
      throw new Error("private");
    });
  const invoke = async (id) => {
    const stamp = String(Date.now()),
      request = "a".repeat(32),
      target = "/api/commands/1/" + id;
    const signature = sign("secret", [
      "http-command",
      "1",
      "request",
      "app",
      "east",
      "key",
      stamp,
      request,
      "POST",
      target,
      "application/json",
      "application/json",
    ]);
    const result = await service.handle(
      "POST",
      target,
      {
        "X-Signature-Version": "1",
        "X-Platform-Id": "east",
        "X-Application-Id": "app",
        "X-Key-Id": "key",
        "X-Timestamp": stamp,
        "X-Request-Id": request,
        "X-Signature": signature,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      Buffer.from('{"applicationId":"spoof"}'),
    );
    assert.equal(
      result.headers.get("x-signature"),
      sign("secret", [
        "http-command",
        "1",
        "response",
        "app",
        "east",
        "key",
        result.headers.get("x-timestamp"),
        request,
        signature,
        String(result.status),
        result.headers.get("content-type"),
      ]),
    );
    return result;
  };
  assert.equal((await (await invoke("Echo")).json()).applicationId, "app");
  const failed = await invoke("Fail");
  assert.equal(failed.status, 500);
  assert.ok(!(await failed.text()).includes("private"));
  assert.equal(service.inFlight, 0);
});
