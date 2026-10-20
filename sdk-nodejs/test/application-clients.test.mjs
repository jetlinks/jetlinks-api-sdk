import test from "node:test";
import assert from "node:assert/strict";
import { Decimal } from "decimal.js";
import { ApplicationClients } from "../dist/index.js";

const state = (changes = {}) => ({
  platformId: "east",
  applicationId: "app",
  createdAt: 1,
  updatedAt: 10,
  keyRevision: 1,
  deletedAt: 0,
  enabled: true,
  apiBaseUrl: "http://127.0.0.1:1",
  accessKey: "key",
  accessSecret: "secret",
  ...changes,
});
const usable = (client) =>
  client.assetScope((s) => s.organization("A")).close();
const closed = (client) =>
  assert.throws(() => usable(client), { code: "INVALID_STATE" });

test("late snapshots cannot replace newer clients across auth modes or scopes", () => {
  const clients = new ApplicationClients(8);
  try {
    const old = clients.get("n", state(), "local");
    const scoped = clients.get("n", state(), "local", []);
    const bearer = clients.get("n", state(), "bearer", null, "user");
    const rotated = state({ keyRevision: 2, accessSecret: "new" });
    const next = clients.get("n", rotated, "local");
    for (const c of [old, scoped, bearer]) closed(c);
    assert.throws(() => clients.get("n", state(), "oauth2", ["unseen"]), {
      code: "INVALID_STATE",
    });
    usable(next);
    const moved = state({
      ...rotated,
      updatedAt: 20,
      apiBaseUrl: "http://127.0.0.1:1/new",
    });
    const latest = clients.get("n", moved, "local");
    closed(next);
    // 凭据较新但地址/状态时间较旧，不能按单一版本或字典序放行。
    assert.throws(
      () => clients.get("n", { ...rotated, keyRevision: 3 }, "local"),
      { code: "INVALID_STATE" },
    );
    assert.throws(
      () => clients.get("n", { ...moved, accessSecret: "conflict" }, "local"),
      { code: "INVALID_STATE" },
    );
    assert.throws(
      () =>
        clients.get(
          "n",
          { ...moved, apiBaseUrl: "http://localhost/conflict" },
          "local",
        ),
      { code: "INVALID_STATE" },
    );
    assert.throws(
      () => clients.get("n", { ...moved, updatedAt: 19, enabled: false }),
      { code: "INVALID_STATE" },
    );
    assert.equal(clients.get("n", moved, "local"), latest);
    usable(latest);
    const high = state({
      ...moved,
      keyRevision: new Decimal("9007199254740993"),
    });
    const precise = clients.get("n", high, "local");
    assert.throws(
      () =>
        clients.get(
          "n",
          { ...high, keyRevision: new Decimal("9007199254740992") },
          "local",
        ),
      { code: "INVALID_STATE" },
    );
    usable(precise);
  } finally {
    clients.close();
  }
});

test("inactive and deleted watermarks survive invalidate; recreation and identities isolate", () => {
  const clients = new ApplicationClients(4);
  try {
    const east = clients.get("n", state(), "local");
    const westState = state({ platformId: "west" });
    const west = clients.get("n", westState, "local");
    assert.throws(
      () => clients.get("n", state({ enabled: false, updatedAt: 20 })),
      { code: "INVALID_ARGUMENT" },
    );
    closed(east);
    usable(west);
    clients.invalidate("n");
    assert.throws(() => clients.get("n", state()), { code: "INVALID_STATE" });
    const enabled = state({ updatedAt: 21 });
    const live = clients.get("n", enabled, "local");
    assert.throws(
      () =>
        clients.get("n", {
          ...enabled,
          deletedAt: 21,
          enabled: false,
          accessKey: undefined,
          accessSecret: undefined,
        }),
      { code: "INVALID_ARGUMENT" },
    );
    closed(live);
    assert.throws(() => clients.get("n", state({ updatedAt: 30 })), {
      code: "INVALID_STATE",
    });
    const recreated = state({ createdAt: 40, updatedAt: 40 });
    const fresh = clients.get("n", recreated, "local");
    assert.throws(
      () =>
        clients.get(
          "n",
          state({ updatedAt: 100, deletedAt: 100, enabled: false }),
        ),
      { code: "INVALID_STATE" },
    );
    usable(fresh);
  } finally {
    clients.close();
  }
});

test("version history is bounded even after client invalidation", () => {
  const clients = new ApplicationClients(1);
  try {
    clients.get("a", state(), "local");
    clients.invalidate("a");
    assert.throws(() => clients.get("b", state(), "local"), {
      code: "LIMIT_EXCEEDED",
    });
    usable(clients.get("a", state(), "local"));
  } finally {
    clients.close();
  }
});
