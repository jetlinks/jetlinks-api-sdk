/** 真实 HTTP 示例，复用 SDK 与唯一前端；管理界面只用于本地/受控联调。 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { setTimeout as pause } from "node:timers/promises";
import {
  ApplicationClients,
  CommandIds,
  CommandService,
  Decimal,
  JetLinksClient,
  NotificationVerifier,
  NotificationReceiver,
  OAuth2,
  SdkError,
  SnapshotSynchronizer,
  active,
  decode,
  encode,
  sha256,
} from "../dist/index.js";
import { ExampleStore, contextId, redact } from "./store.mjs";

const config = decode(
  fs.readFileSync(process.argv[2] || "example.local.json", "utf8"),
);
const privateKey = (key) => {
  const trust = config.templates.find((t) => t.receiverKeyId === key);
  if (!trust) throw new SdkError("INVALID_ARGUMENT", "Unknown receiver key");
  return fs.readFileSync(trust.privateKeyFile, "utf8");
};
const store = new ExampleStore(config.database, privateKey),
  clients = new ApplicationClients();
const snapshots = new Map(),
  records = [],
  pending = new Map(),
  sessions = new Map();
const apiStates = () =>
  config.apiApplications.map((a) => ({
    ...a,
    platformId: "",
    createdAt: 0,
    keyRevision: 1,
    updatedAt: 0,
    deletedAt: 0,
    enabled: true,
  }));
const states = () => [...apiStates(), ...store.applications()];
const idOf = (s) => s.contextId || contextId(s);
const find = (id) => {
  const state = states().find((s) => idOf(s) === id);
  if (!state) throw new SdkError("INVALID_ARGUMENT", "Unknown application");
  return state;
};
const template = (state) =>
  config.templates.find(
    (t) =>
      t.platformId === state.platformId && t.templateId === state.templateId,
  );
const configuredToken = (state, mode) =>
  state[mode === "management" ? "managementToken" : "userToken"] ||
  template(state)?.userToken;
const clientFor = (state, mode = "oauth2") =>
  ["management", "user"].includes(mode)
    ? clients.get(
        idOf(state) + ":" + mode,
        state,
        "bearer",
        null,
        configuredToken(state, mode) || "",
      )
    : clients.get(idOf(state), state, mode);
const publicState = (state) => ({
  contextId: idOf(state),
  type: state.templateId ? "business" : "api",
  platformId: state.platformId,
  applicationId: state.applicationId,
  apiBaseUrl: state.apiBaseUrl,
  keyRevision: state.keyRevision,
  enabled: state.enabled,
  deleted: !!state.deletedAt,
  credentialReady: active(state),
  notificationUrl:
    config.publicBaseUrl.replace(/\/$/, "") +
    "/jetlinks/events" +
    (state.templateId ? "" : "/" + state.contextId),
});
const collect = async (source) => {
  const values = [];
  for await (const value of source) {
    values.push(value);
    if (values.length > 10000)
      throw new SdkError("LIMIT_EXCEEDED", "Example snapshot exceeds limit");
  }
  return values;
};
const commands = new CommandService((platform, app) =>
  store.find(platform, app),
);
function register(id, stream, handler) {
  commands.register(id, stream, (state, input, signal) => {
    const record = {
      commandId: id,
      platformId: state.platformId,
      applicationId: state.applicationId,
      receivedAt: Date.now(),
      completion: "running",
      result: stream ? [] : null,
    };
    records.unshift(record);
    records.splice(100);
    const display = (value) =>
      Buffer.byteLength(encode(value)) <= 65536
        ? redact(value)
        : { truncated: true };
    if (stream)
      return (async function* () {
        try {
          for await (const value of handler(state, input, signal)) {
            if (record.result.length < 100) record.result.push(display(value));
            yield value;
          }
          record.completion = "complete";
        } catch (error) {
          record.completion = "failed";
          throw error;
        } finally {
          if (record.completion === "running") record.completion = "cancelled";
        }
      })();
    return Promise.resolve()
      .then(() => handler(state, input, signal))
      .then(
        (value) => {
          record.result = display(value);
          record.completion = "complete";
          return value;
        },
        (error) => {
          record.completion = "failed";
          throw error;
        },
      );
  });
}
register(CommandIds.GET_SERVICE_HEALTH, false, () => {
  store.db.prepare("SELECT 1").get();
  return { status: "UP" };
});
register(CommandIds.GET_SERVICE_INFO, false, () => ({
  name: "JetLinks Node.js Example",
  version: "2.0.0-alpha.0",
}));
register(CommandIds.GET_TENANT_RESOURCES, true, async function* () {
  yield {
    id: "example",
    name: "示例资源",
    metrics: [
      {
        id: "requests",
        name: "示例资源使用次数",
        unit: "count",
        type: "request",
        limitType: "quotaTimeLimit",
      },
    ],
    capabilities: [{ id: "use", name: "使用资源" }],
  };
});
register(CommandIds.READ_TENANT_USAGE, true, async function* (state, input) {
  yield* store.usage(state, input);
});
register(CommandIds.SAVE_TENANT_LIMITS, false, (state, input) =>
  store.saveLimits(state, input),
);
register("UseExampleResource", false, (state, input) =>
  store.use(state, input),
);
register("Echo", false, (_state, input) => input);
register("WatchUsage", true, async function* (_state, input, signal) {
  for (
    let index = 0;
    index < Math.min(100, Math.max(1, input.limit || 1));
    index++
  ) {
    await pause(200, undefined, { signal });
    yield { index, timestamp: Date.now() };
  }
});

const verifier = (channel) =>
  new NotificationVerifier((platform, app, keyId) => {
    if (!platform) {
      const api = config.apiApplications.find(
        (a) =>
          a.contextId === channel &&
          a.applicationId === app &&
          a.accessKey === keyId,
      );
      return (
        api && {
          type: "api",
          applicationId: app,
          channelId: channel,
          secret: api.accessSecret,
        }
      );
    }
    if (channel) return;
    const trust = config.templates.find(
      (t) => t.platformId === platform && t.notificationKeyId === keyId,
    );
    return (
      trust && {
        type: "business",
        platformId: platform,
        templateId: trust.templateId,
        secret: trust.notificationSecret,
        channelId: "",
      }
    );
  });
const rawHeaders = (request) => {
  const result = {};
  for (let i = 0; i < request.rawHeaders.length; i += 2)
    (result[request.rawHeaders[i].toLowerCase()] ??= []).push(
      request.rawHeaders[i + 1],
    );
  return result;
};
const json = (response, value, status = 200, headers = {}) => {
  response.writeHead(status, {
    "Content-Type": "application/json",
    ...headers,
  });
  response.end(encode(redact(value)));
};
const cookie = (request, name) =>
  (request.headers.cookie || "")
    .split(";")
    .map((v) => v.trim())
    .find((v) => v.startsWith(name + "="))
    ?.slice(name.length + 1);
function putBounded(map, key, value) {
  for (const [id, entry] of map) if (entry.expires < Date.now()) map.delete(id);
  if (map.size >= 128)
    throw new SdkError("LIMIT_EXCEEDED", "Example login capacity reached");
  map.set(key, value);
}
async function writeStream(response, source) {
  const readable = Readable.from(source);
  response.on("close", () => readable.destroy());
  await pipeline(readable, response);
}
async function authorize(state, input) {
  const id = idOf(state),
    business = clientFor(state).businessApplication(state.applicationId);
  if (input.operation === "roleAuthorization")
    return collect(business.roleAuthorization(input.roleId));
  if (input.operation === "roleMenus")
    return collect(business.roleMenus(input.roleId));
  if (input.operation === "assets") return business.assets(input.roleId);
  const fingerprint = sha256(encode(state));
  let sync = snapshots.get(id);
  if (!sync || sync.fingerprint !== fingerprint) {
    const synchronizer = new SnapshotSynchronizer(async function* () {
      const [members, roles, userRoles, grants] = await Promise.all([
        collect(business.members()),
        collect(business.roles()),
        collect(business.userRoles()),
        collect(business.grants()),
      ]);
      yield { members, roles, userRoles, grants };
    }, 1);
    sync = { fingerprint, synchronizer };
    snapshots.set(id, sync);
  }
  return (await sync.synchronizer.refresh())[0];
}
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, "http://localhost"),
      route = url.pathname;
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 1048576)
        throw new SdkError("LIMIT_EXCEEDED", "Request exceeds limit");
      chunks.push(chunk);
    }
    const raw = Buffer.concat(chunks);
    if (route.startsWith("/jetlinks/events")) {
      const channel =
        route === "/jetlinks/events"
          ? ""
          : decodeURIComponent(route.slice("/jetlinks/events/".length));
      const receiver = new NotificationReceiver(verifier(channel), {
        accept: async (notice) => {
          const before = store.find(notice.platformId, notice.applicationId);
          const fresh = await store.accept(notice);
          if (fresh && notice.key.type === "business") {
            const after = store.find(notice.platformId, notice.applicationId),
              id = contextId(after);
            const connectionState = (s) =>
              s &&
              encode([
                s.createdAt,
                s.apiBaseUrl,
                s.accessKey,
                s.accessSecret,
                s.keyRevision,
                s.enabled,
                s.deletedAt,
              ]);
            if (connectionState(before) !== connectionState(after)) {
              clients.invalidate(id);
              clients.invalidate(id + ":user");
              clients.invalidate(id + ":management");
            }
            snapshots.delete(id);
          }
          return fresh;
        },
      });
      try {
        await receiver.receive(rawHeaders(request), raw);
        response.writeHead(204);
        return response.end();
      } catch (error) {
        if (error instanceof SdkError && error.code === "AUTHENTICATION")
          store.receipt({
            verified: false,
            accepted: false,
            rawBody: raw.toString(),
          });
        throw error;
      }
    }
    if (route === "/api/capabilities" || route.startsWith("/api/commands/")) {
      const result = await commands.handle(
        request.method,
        request.url,
        rawHeaders(request),
        raw,
      );
      response.writeHead(result.status, Object.fromEntries(result.headers));
      try {
        return await writeStream(response, Readable.fromWeb(result.body));
      } finally {
        await result.body?.cancel().catch(() => {});
      }
    }
    const input = raw.length ? decode(raw.toString()) : {};
    if (route === "/example-api/info")
      return json(response, {
        language: "nodejs",
        sdkVersion: "2.0.0-alpha.0",
        features: [
          "requests",
          "notifications",
          "login",
          "authorization",
          "subscriptions",
          "commands",
        ],
        clientModes: [{ value: "promise", label: "Promise / AsyncIterable" }],
        syncStatus: "on-demand",
      });
    if (route === "/example-api/applications")
      return json(response, states().map(publicState));
    if (route === "/example-api/notifications")
      return json(response, store.receipts(url.searchParams));
    if (route.startsWith("/example-api/notifications/"))
      return json(response, store.detail(route.split("/").pop()) || {}, 200);
    if (route === "/example-api/session") {
      const id = cookie(request, "jl-session"),
        session = sessions.get(id);
      if (request.method === "DELETE") {
        sessions.delete(id);
        return json(response, { authenticated: false }, 200, {
          "Set-Cookie":
            "jl-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
        });
      }
      return json(
        response,
        session &&
          session.expires > Date.now() &&
          active(find(session.contextId))
          ? {
              authenticated: true,
              contextId: session.contextId,
              context: session.context,
            }
          : { authenticated: false },
      );
    }
    if (route === "/example-api/login/callback") {
      const flow = pending.get(cookie(request, "jl-pending"));
      pending.delete(cookie(request, "jl-pending"));
      if (
        !flow ||
        flow.expires < Date.now() ||
        (!flow.platform && flow.auth.state !== url.searchParams.get("state")) ||
        (flow.platform &&
          (flow.state.platformId !== url.searchParams.get("platformId") ||
            flow.state.applicationId !== url.searchParams.get("applicationId")))
      )
        throw new SdkError("AUTHENTICATION", "Invalid login state");
      const current = find(flow.contextId);
      if (
        !active(current) ||
        current.createdAt !== flow.state.createdAt ||
        !new Decimal(current.keyRevision).eq(flow.state.keyRevision)
      )
        throw new SdkError(
          "AUTHENTICATION",
          "Application changed during login",
        );
      const oauth = new OAuth2(
        current.apiBaseUrl,
        current.accessKey,
        current.accessSecret,
      );
      try {
        const token = await oauth.exchange(
          url.searchParams.get("code"),
          flow.auth.redirectUri,
          flow.platform ? undefined : flow.auth.verifier,
        );
        const user = new JetLinksClient(current.apiBaseUrl, {
          token: token.access_token,
        });
        try {
          const context = await user.authorizationContext();
          if (
            context.application?.id !== current.applicationId ||
            context.subject?.type !== "user" ||
            !context.subject.id
          )
            throw new SdkError(
              "AUTHENTICATION",
              "Authorization context mismatch",
            );
          const id = crypto.randomUUID();
          putBounded(sessions, id, {
            contextId: flow.contextId,
            context,
            expires: Date.now() + Math.min(900, token.expires_in) * 1000,
          });
          response.writeHead(302, {
            Location: "/",
            "Set-Cookie": `jl-session=${id}; HttpOnly; SameSite=Lax; Path=/; Max-Age=900`,
          });
          return response.end();
        } finally {
          user.close();
        }
      } finally {
        oauth.close();
      }
    }
    const match = route.match(
      /^\/example-api\/applications\/([^/]+)(?:\/(.*))?$/,
    );
    if (match) {
      const state = find(decodeURIComponent(match[1])),
        action = match[2];
      if (!action) return json(response, publicState(state));
      if (!active(state))
        throw new SdkError("INVALID_ARGUMENT", "Inactive application");
      if (action === "commands")
        return json(response, {
          capabilities: commands.capabilities(state),
          records: records.filter(
            (v) =>
              v.platformId === state.platformId &&
              v.applicationId === state.applicationId,
          ),
        });
      if (action === "authorization")
        return json(response, await authorize(state, input));
      if (action === "login") {
        const oauth = new OAuth2(
            state.apiBaseUrl,
            state.accessKey,
            state.accessSecret,
          ),
          user = new JetLinksClient(state.apiBaseUrl, {
            token: configuredToken(state, "user") || "",
          });
        try {
          const auth = oauth.authorization(
              config.publicBaseUrl.replace(/\/$/, "") +
                "/example-api/login/callback",
            ),
            platform = input.entry === "platform";
          const destination = new URL(auth.url);
          const location = platform
            ? (
                await user.request(
                  "/business-application/" +
                    encodeURIComponent(state.applicationId) +
                    "/_redirect",
                )
              ).location
            : await user.request(
                destination.pathname.slice(
                  new URL(state.apiBaseUrl).pathname.length,
                ),
                { query: Object.fromEntries(destination.searchParams) },
              );
          const id = crypto.randomUUID();
          putBounded(pending, id, {
            contextId: idOf(state),
            state: { ...state },
            auth,
            platform,
            expires: Date.now() + 600000,
          });
          return json(response, { url: location }, 200, {
            "Set-Cookie": `jl-pending=${id}; HttpOnly; SameSite=Lax; Path=/; Max-Age=600`,
          });
        } finally {
          user.close();
          oauth.close();
        }
      }
      const client = clientFor(
        state,
        action === "subscriptions" ? "management" : input.authMode || "oauth2",
      );
      if (action === "diagnostics")
        return json(response, {
          tokenRefreshes: client.tokenProvider.refreshes || 0,
          tokenCacheHits: client.tokenProvider.hits || 0,
          commandsInFlight: commands.inFlight,
          syncStatus: "on-demand",
          snapshotAvailable: !!snapshots.get(idOf(state))?.synchronizer.current
            .length,
        });
      if (action === "subscriptions") {
        const subscriptions = client.subscriptions(state.applicationId),
          op = input.operation || "query";
        const calls = {
          query: () => subscriptions.query(),
          get: () => subscriptions.get(input.id),
          create: () => subscriptions.create(input.body),
          update: () => subscriptions.update(input.id, input.body),
          validate: () => subscriptions.validate(input.body),
          enable: () => subscriptions.enable(input.id),
          disable: () => subscriptions.enable(input.id, false),
          delete: () => subscriptions.delete(input.id),
          events: () => subscriptions.events(),
          channels: () => subscriptions.channels(),
          tasks: () => subscriptions.tasks(input.id),
          records: () => subscriptions.records(input.id),
          payload: () =>
            subscriptions.payload(input.id, input.taskId, input.attempt),
        };
        if (!calls[op])
          throw new SdkError(
            "INVALID_ARGUMENT",
            "Unknown subscription operation",
          );
        return json(response, await calls[op]());
      }
      if (action === "requests") {
        const options = { method: input.method || "GET", body: input.body };
        if (input.stream) {
          response.writeHead(200, { "Content-Type": "application/x-ndjson" });
          const source = async function* () {
            for await (const item of client.stream(input.path, options))
              yield encode(redact(item)) + "\n";
          };
          return await writeStream(response, source());
        }
        const start = Date.now(),
          value = await client.request(
            input.path || "/authorize/context",
            options,
          );
        return json(response, {
          durationMs: Date.now() - start,
          result: value,
        });
      }
    }
    if (route.startsWith("/example-api/"))
      return json(response, { error: "Unknown route" }, 404);
    const root = path.resolve(config.uiDir),
      file = path.resolve(
        root,
        "." + decodeURIComponent(route === "/" ? "/index.html" : route),
      );
    if (
      !file.startsWith(root + path.sep) ||
      !fs.existsSync(file) ||
      !fs.statSync(file).isFile()
    )
      return json(response, { error: "Not found" }, 404);
    response.writeHead(200, {
      "Content-Type":
        {
          ".html": "text/html",
          ".js": "application/javascript",
          ".css": "text/css",
        }[path.extname(file)] || "application/octet-stream",
    });
    fs.createReadStream(file).pipe(response);
  } catch (error) {
    if (response.headersSent) {
      response.destroy();
      return;
    }
    const status =
      error instanceof SdkError
        ? error.code === "AUTHENTICATION"
          ? 401
          : error.status || (error.code === "LIMIT_EXCEEDED" ? 429 : 400)
        : 500;
    json(
      response,
      {
        error:
          error instanceof SdkError ? error.code : "Example request failed",
      },
      status,
    );
  }
});
server.listen(config.port, config.host || "127.0.0.1", () =>
  console.log(`Node.js Example: ${config.publicBaseUrl}`),
);
process.on("SIGTERM", () => {
  server.closeAllConnections();
  server.close(() => {
    clients.close();
    store.close();
    process.exit(0);
  });
});
