import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ExampleStore } from "../examples/store.mjs";
import {
  NotificationReceiver,
  NotificationVerifier,
  decode,
  sha256,
} from "../dist/index.js";
import { sign } from "../../examples/smoke/platform-fixture.mjs";

test("quota revisions preserve adjacent 64-bit integers across persistence", () => {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "jetlinks-node-quota-"),
  );
  const file = path.join(directory, "database");
  const state = { platformId: "east", applicationId: "app", createdAt: 1 };
  let store = new ExampleStore(file, () => "");
  const snapshot = (revision) =>
    decode(`{"revision":${revision},"limits":[],"capabilities":[]}`);
  try {
    store.saveLimits(state, snapshot("9007199254740992"));
    assert.ok(
      store
        .saveLimits(state, snapshot("9007199254740993"))
        .revision.eq("9007199254740993"),
    );
    store.close();
    store = new ExampleStore(file, () => "");
    assert.ok(
      store
        .saveLimits(state, snapshot("9007199254740992"))
        .revision.eq("9007199254740993"),
    );
  } finally {
    store.close();
    fs.rmSync(directory, { recursive: true });
  }
});

test("failed inbox insert rolls back application binding; retry is durable and deduplicated", async () => {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "jetlinks-node-store-"),
  );
  const file = path.join(directory, "database");
  let store = new ExampleStore(file, () => "");
  const event = {
    id: "event",
    type: "application.updated",
    occurredAt: Date.now(),
    data: {
      application: {
        id: "app",
        templateId: "template",
        createTime: 1,
        state: "enabled",
        apiBaseUrl: "http://localhost/gateway",
      },
    },
  };
  const raw = Buffer.from(JSON.stringify(event)),
    stamp = String(Date.now());
  const headers = {
    "X-Signature-Version": "1",
    "X-Platform-Id": "east",
    "X-Application-Id": "app",
    "X-Key-Id": "key",
    "X-Timestamp": stamp,
    "X-Event-Id": "event",
    "X-Signature": sign("secret", [
      "1",
      "app",
      "east",
      "key",
      stamp,
      "event",
      sha256(raw),
    ]),
  };
  const verifier = new NotificationVerifier(() => ({
    type: "business",
    platformId: "east",
    templateId: "template",
    channelId: "",
    secret: "secret",
  }));
  try {
    store.db.exec(
      "CREATE TRIGGER fail_inbox BEFORE INSERT ON inbox BEGIN SELECT RAISE(FAIL,'unavailable'); END",
    );
    await assert.rejects(
      new NotificationReceiver(verifier, store).receive(headers, raw),
    );
    assert.equal(store.find("east", "app"), undefined);
    assert.equal(store.receipts(new URLSearchParams()).total, 0);
    store.db.exec("DROP TRIGGER fail_inbox");
    assert.equal(
      await new NotificationReceiver(verifier, store).receive(headers, raw),
      true,
    );
    store.close();
    store = new ExampleStore(file, () => "");
    assert.equal(
      await new NotificationReceiver(verifier, store).receive(headers, raw),
      false,
    );
    assert.equal(store.find("east", "app").applicationId, "app");
  } finally {
    store.close();
    fs.rmSync(directory, { recursive: true });
  }
});
