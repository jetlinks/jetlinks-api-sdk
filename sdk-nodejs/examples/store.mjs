/** 单进程 SQLite 示例；inbox、绑定与应用状态在同一事务提交。驱动仅属于示例。 */
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import {
  Decimal,
  SdkError,
  applyLifecycle,
  decode,
  encode,
  sha256,
  lp,
} from "../dist/index.js";

Decimal.set({ precision: 100 });
export const identity = (platform, app) =>
  Buffer.from(encode([platform, app])).toString("base64url");
export const contextId = (state) =>
  "business-" + identity(state.platformId, state.applicationId);
export class ExampleStore {
  constructor(file, privateKey) {
    fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    this.privateKey = privateKey;
    this.db = new DatabaseSync(file + ".sqlite");
    this.db.exec(
      "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS apps(id TEXT PRIMARY KEY, body TEXT NOT NULL); CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY); CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, received INTEGER NOT NULL, body TEXT NOT NULL); CREATE TABLE IF NOT EXISTS quotas(id TEXT PRIMARY KEY, body TEXT NOT NULL)",
    );
  }
  transaction(action) {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const result = action();
      this.db.exec("COMMIT");
      return result;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }
  find(platform, app) {
    const row = this.db
      .prepare("SELECT body FROM apps WHERE id=?")
      .get(identity(platform, app));
    return row && decode(row.body);
  }
  applications() {
    return this.db
      .prepare("SELECT body FROM apps ORDER BY id")
      .all()
      .map((row) => decode(row.body));
  }
  async accept(notice) {
    return this.transaction(() => {
      const key = sha256(
        lp([
          notice.key.channelId,
          notice.platformId,
          notice.applicationId,
          notice.eventId,
        ]),
      );
      const duplicate = !!this.db
        .prepare("SELECT 1 FROM inbox WHERE id=?")
        .get(key);
      if (!duplicate) {
        if (notice.key.type === "business") {
          const state = applyLifecycle(
            this.find(notice.platformId, notice.applicationId),
            notice,
            this.privateKey,
          );
          this.db
            .prepare("INSERT OR REPLACE INTO apps VALUES(?,?)")
            .run(
              identity(notice.platformId, notice.applicationId),
              encode(state),
            );
        }
        this.db.prepare("INSERT INTO inbox VALUES(?)").run(key);
      }
      this.receipt({
        channelId: notice.key.channelId,
        platformId: notice.platformId,
        applicationId: notice.applicationId,
        eventId: notice.eventId,
        type: notice.event.type,
        verified: true,
        accepted: true,
        duplicate,
        rawBody: Buffer.from(notice.rawBody).toString(),
        rawBodySha256: sha256(notice.rawBody),
        event: notice.event,
        signatureHeaders: notice.signatureHeaders,
      });
      return !duplicate;
    });
  }
  receipt(value) {
    value = { ...value, recordId: crypto.randomUUID(), receivedAt: Date.now() };
    this.db
      .prepare("INSERT INTO receipts VALUES(?,?,?)")
      .run(value.recordId, value.receivedAt, encode(value));
  }
  receipts(filters) {
    let values = this.db
      .prepare("SELECT body FROM receipts ORDER BY received DESC, rowid DESC")
      .all()
      .map((row) => decode(row.body));
    values = values.filter((row) =>
      [...filters].every(
        ([k, v]) =>
          !v ||
          k === "pageIndex" ||
          (k === "status"
            ? v === "duplicate"
              ? row.duplicate
              : v === "accepted"
                ? row.accepted && !row.duplicate
                : !row.accepted
            : row[k] === v),
      ),
    );
    const total = values.length,
      page = Math.max(0, Number(filters.get("pageIndex") || 0));
    return { total, data: values.slice(page * 50, (page + 1) * 50) };
  }
  detail(id) {
    const row = this.db.prepare("SELECT body FROM receipts WHERE id=?").get(id);
    return row && decode(row.body);
  }
  quota(state) {
    const key =
      identity(state.platformId, state.applicationId) + ":" + state.createdAt;
    const row = this.db.prepare("SELECT body FROM quotas WHERE id=?").get(key);
    return {
      key,
      value: row
        ? decode(row.body)
        : {
            revision: -1,
            limits: [],
            capabilities: [],
            usage: new Decimal(0),
            windowStart: -1,
          },
    };
  }
  saveLimits(state, input) {
    if (
      (typeof input.revision !== "number" &&
        !Decimal.isDecimal(input.revision)) ||
      !new Decimal(input.revision).isInteger() ||
      new Decimal(input.revision).lt(0) ||
      !Array.isArray(input.limits) ||
      !Array.isArray(input.capabilities)
    )
      throw new SdkError(
        "INVALID_ARGUMENT",
        "Complete quota snapshot required",
      );
    if (
      input.limits.length > 1 ||
      input.limits.some(
        (v) =>
          v.metricId !== "example:requests" ||
          v.limit == null ||
          !new Decimal(v.limit).isFinite() ||
          !(v.windowDurationMillis > 0),
      ) ||
      input.capabilities.length > 1 ||
      input.capabilities.some(
        (v) =>
          v.resource !== "example" ||
          !Array.isArray(v.capabilities) ||
          v.capabilities.some((c) => c !== "use"),
      )
    )
      throw new SdkError("INVALID_ARGUMENT", "Invalid example quota");
    return this.transaction(() => {
      const { key, value } = this.quota(state);
      if (new Decimal(input.revision).gt(value.revision)) {
        Object.assign(value, {
          revision: input.revision,
          limits: input.limits,
          capabilities: input.capabilities,
        });
        this.db
          .prepare("INSERT OR REPLACE INTO quotas VALUES(?,?)")
          .run(key, encode(value));
      }
      return { revision: value.revision };
    });
  }
  snapshot(value) {
    const timestamp = Date.now(),
      duration = value.limits[0].windowDurationMillis,
      start = Math.floor(timestamp / duration) * duration;
    return {
      metricId: "example:requests",
      usage: new Decimal(value.windowStart === start ? value.usage : 0),
      timestamp,
      windowDurationMillis: duration,
      nextResetTime: start + duration,
    };
  }
  usage(state, input) {
    const { value } = this.quota(state);
    return !value.limits.length ||
      (input.metricIds?.length && !input.metricIds.includes("example:requests"))
      ? []
      : [this.snapshot(value)];
  }
  use(state, input) {
    const amount = new Decimal(input.amount ?? 0);
    if (!amount.isFinite() || !amount.isPositive())
      throw new SdkError("INVALID_ARGUMENT", "Positive amount required");
    return this.transaction(() => {
      const { key, value } = this.quota(state);
      if (
        !value.limits.length ||
        !value.capabilities.some(
          (v) => v.resource === "example" && v.capabilities.includes("use"),
        )
      )
        throw new SdkError("INVALID_STATE", "Resource not granted");
      const snapshot = this.snapshot(value),
        usage = snapshot.usage.plus(amount),
        limit = new Decimal(value.limits[0].limit);
      if (!limit.isNegative() && usage.gt(limit))
        throw new SdkError("LIMIT_EXCEEDED", "Quota exceeded");
      Object.assign(value, {
        usage,
        windowStart: snapshot.nextResetTime - snapshot.windowDurationMillis,
      });
      this.db
        .prepare("INSERT OR REPLACE INTO quotas VALUES(?,?)")
        .run(key, encode(value));
      return { ...snapshot, usage };
    });
  }
  close() {
    this.db.close();
  }
}

export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (!value || typeof value !== "object" || Decimal.isDecimal(value))
    return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, v]) => {
      if (
        /^(access_?secret|notificationSecret|client_?secret|secret|access_?token|refresh_?token|id_?token|token|authorization|cookie|set-cookie|encryptedAccessSecret|access_?key|x-signature)$/i.test(
          key,
        )
      )
        return [key, "[redacted]"];
      if (key === "rawBody") {
        try {
          return [key, encode(redact(decode(v)))];
        } catch {
          return [key, "[unparseable]"];
        }
      }
      return [key, redact(v)];
    }),
  );
}
