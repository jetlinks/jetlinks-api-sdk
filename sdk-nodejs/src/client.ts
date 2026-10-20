import crypto from "node:crypto";
import { Readable, pipeline, type Transform } from "node:stream";
import { createGunzip, createInflate, createBrotliDecompress } from "node:zlib";
import { request as httpRequest, Agent, type Dispatcher } from "undici";
import {
  configureScope,
  scopeHeaders,
  type AssetScopeConfigurer,
} from "./asset-scope.js";
import {
  SdkError,
  decode,
  encode,
  localToken,
  target,
  segment,
  sha256,
} from "./protocol.js";
import {
  active,
  type ApplicationState,
  type Page,
  type Query,
  type AuthorizationContext,
  type Member,
  type Role,
  type UserRole,
  type ApiGrant,
  type RoleAuthorization,
  type RoleMenu,
  type AssetAccesses,
  type SubscriptionConfig,
  type Subscription,
  type SubscriptionValidation,
  type DeliveryTask,
  type DeliveryRecord,
  type DeliveryPayload,
  type EventProvider,
  type DeliveryChannel,
} from "./models.js";

export interface TokenProvider {
  token(): Promise<string>;
  invalidate?(): void;
}
/** 每个身份/Scope 一份缓存；取消某次业务请求不会取消共享 Token 刷新。 */
export class CachedTokenProvider implements TokenProvider {
  private cached?: { token: string; expiresAt: number };
  private inFlight?: Promise<string>;
  private retryAt = 0;
  private generation = 0;
  refreshes = 0;
  hits = 0;
  constructor(
    private readonly loader: () => Promise<{
      token: string;
      expiresAt: number;
    }>,
    private readonly skew = 5000,
  ) {}
  async token(): Promise<string> {
    if (this.cached && Date.now() < this.cached.expiresAt - this.skew) {
      this.hits++;
      return this.cached.token;
    }
    if (this.inFlight && (!this.retryAt || Date.now() < this.retryAt))
      return this.inFlight;
    const generation = this.generation;
    this.retryAt = 0;
    this.inFlight = Promise.resolve().then(async () => {
      this.refreshes++;
      try {
        const result = await this.loader();
        if (!result.token || result.expiresAt <= Date.now())
          throw new SdkError("SERIALIZATION", "Invalid token response");
        if (generation === this.generation) {
          this.cached = result;
          this.inFlight = undefined;
        }
        return result.token;
      } catch (error) {
        if (generation === this.generation) this.retryAt = Date.now() + 1000;
        throw error;
      }
    });
    return this.inFlight;
  }
  invalidate(): void {
    this.generation++;
    this.cached = undefined;
    this.inFlight = undefined;
    this.retryAt = 0;
  }
}
export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  query?: Record<string, string>;
  raw?: boolean;
  signal?: AbortSignal;
  /** 按维度覆盖客户端默认资产范围。 */
  assetScope?: AssetScopeConfigurer;
}

// Fetch 会把重复 Header 合并为逗号列表；request 保留各维度独立的线上 Header。
interface PlatformResponse {
  status: number;
  ok: boolean;
  body: ReadableStream<Uint8Array>;
}
function responseBody(
  body: Readable,
  encoding: string | string[] | undefined,
): ReadableStream<Uint8Array> {
  const transforms: Transform[] = [];
  for (const name of String(encoding ?? "")
    .split(",")
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value && value !== "identity")
    .reverse()) {
    if (name === "gzip" || name === "x-gzip") transforms.push(createGunzip());
    else if (name === "deflate") transforms.push(createInflate());
    else if (name === "br") transforms.push(createBrotliDecompress());
    else return Readable.toWeb(body) as ReadableStream<Uint8Array>;
  }
  // pipeline 双向传递解压错误和取消，响应/单行大小限制作用于解压后的数据。
  if (transforms.length) pipeline([body, ...transforms], () => {});
  return Readable.toWeb(
    transforms.at(-1) ?? body,
  ) as ReadableStream<Uint8Array>;
}
export interface ClientOptions {
  token?: string | TokenProvider;
  timeout?: number;
  maxBytes?: number;
  maxConcurrency?: number;
}
/** 可信地址绑定客户端；不跟随重定向、不重试带副作用请求。close 会取消在途传输。 */
export class JetLinksClient {
  private shared: {
    controllers: Set<AbortController>;
    closed: boolean;
    transport?: Agent;
  } = {
    controllers: new Set<AbortController>(),
    closed: false,
  };
  private owner = true;
  private assetScopes: ReadonlyMap<string, string> = new Map();
  private closed = false;
  readonly timeout: number;
  readonly maxBytes: number;
  readonly maxConcurrency: number;
  readonly tokenProvider: string | TokenProvider;
  private owned?: OAuth2;
  constructor(
    readonly baseUrl: string,
    options: ClientOptions = {},
  ) {
    target(baseUrl, "/");
    this.timeout = options.timeout ?? 30000;
    this.maxBytes = options.maxBytes ?? 1024 * 1024;
    this.maxConcurrency = options.maxConcurrency ?? 32;
    this.tokenProvider = options.token ?? "";
    if (Math.min(this.timeout, this.maxBytes, this.maxConcurrency) < 1)
      throw new SdkError("INVALID_ARGUMENT", "Invalid client limits");
  }
  /** 独立范围视图共享 Token、连接与并发预算；关闭视图不关闭根客户端。 */
  assetScope(action: AssetScopeConfigurer): JetLinksClient {
    this.ensureOpen();
    const view = new JetLinksClient(this.baseUrl, {
      token: this.tokenProvider,
      timeout: this.timeout,
      maxBytes: this.maxBytes,
      maxConcurrency: this.maxConcurrency,
    });
    view.shared = this.shared;
    view.owner = false;
    view.assetScopes = configureScope(this.assetScopes, action);
    return view;
  }
  private ensureOpen(): void {
    if (this.closed || this.shared.closed)
      throw new SdkError("INVALID_STATE", "Client closed");
  }
  static local(
    base: string,
    key: string,
    secret: string,
    scopes: string[] | null = null,
  ): JetLinksClient {
    return new JetLinksClient(base, {
      token: new CachedTokenProvider(async () => {
        const now = Date.now();
        return {
          token: localToken(key, secret, scopes, now),
          expiresAt: now + 120000,
        };
      }),
    });
  }
  static oauth2(
    base: string,
    key: string,
    secret: string,
    scopes: string[] | null = null,
  ): JetLinksClient {
    const oauth = new OAuth2(base, key, secret);
    const client = new JetLinksClient(base, {
      token: oauth.machineTokens(scopes),
    });
    client.owned = oauth;
    return client;
  }
  private async open(
    path: string,
    options: RequestOptions,
  ): Promise<{
    response: PlatformResponse;
    finish: () => void;
    controller: AbortController;
  }> {
    this.ensureOpen();
    const url = target(this.baseUrl, path, options.query);
    const headers = scopeHeaders(
      this.assetScopes,
      options.headers,
      options.assetScope,
    );
    const token =
      typeof this.tokenProvider === "string"
        ? this.tokenProvider
        : await this.tokenProvider.token();
    this.ensureOpen();
    if (this.shared.controllers.size >= this.maxConcurrency)
      throw new SdkError("LIMIT_EXCEEDED", "Client concurrency exceeded");
    const controller = new AbortController();
    this.shared.controllers.add(controller);
    const abort = () => controller.abort(options.signal?.reason);
    options.signal?.addEventListener("abort", abort, { once: true });
    if (options.signal?.aborted) abort();
    const finish = () => {
      this.shared.controllers.delete(controller);
      options.signal?.removeEventListener("abort", abort);
    };
    const timer = setTimeout(() => controller.abort(), this.timeout);
    try {
      if (!("accept" in headers)) headers.accept = "application/json";
      if (token) headers.authorization = "Bearer " + token;
      const body =
        options.body === undefined
          ? undefined
          : typeof options.body === "string"
            ? options.body
            : encode(options.body);
      if (body !== undefined && !("content-type" in headers))
        headers["content-type"] = "application/json";
      const result = await httpRequest(url, {
        dispatcher: (this.shared.transport ??= new Agent({
          connections: this.maxConcurrency,
        })),
        method: (options.method ?? "GET") as Dispatcher.HttpMethod,
        headers,
        body,
        headersTimeout: this.timeout,
        bodyTimeout: this.timeout,
        signal: controller.signal,
      });
      const response: PlatformResponse = {
        status: result.statusCode,
        ok: result.statusCode >= 200 && result.statusCode < 300,
        body: responseBody(result.body, result.headers["content-encoding"]),
      };
      return { response, finish, controller };
    } catch (cause) {
      finish();
      throw new SdkError(
        "TRANSPORT",
        "Platform transport failed",
        0,
        undefined,
        { cause },
      );
    } finally {
      clearTimeout(timer);
    }
  }
  private async *bytes(
    response: PlatformResponse,
    controller: AbortController,
  ): AsyncGenerator<Uint8Array> {
    const reader = response.body?.getReader();
    if (!reader) return;
    try {
      while (true) {
        const timer = setTimeout(() => controller.abort(), this.timeout);
        let next;
        try {
          next = await reader.read();
        } finally {
          clearTimeout(timer);
        }
        if (next.done) return;
        yield next.value;
      }
    } catch (cause) {
      throw new SdkError(
        "TRANSPORT",
        "Response transport failed",
        0,
        undefined,
        { cause },
      );
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
  }
  private unwrap<T>(
    response: PlatformResponse,
    value: unknown,
    raw = false,
  ): T {
    const doc = value as { status?: number; result?: unknown } | null;
    if (
      !response.ok ||
      (!raw && doc?.status !== undefined && doc.status !== 200)
    )
      throw new SdkError(
        "REMOTE_ERROR",
        `JetLinks request failed (status ${doc?.status ?? response.status})`,
        doc?.status ?? response.status,
        value,
      );
    return (
      !raw && doc && Object.hasOwn(doc, "result") ? doc.result : value
    ) as T;
  }
  async request<T = unknown>(
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    const { response, controller, finish } = await this.open(path, options);
    try {
      const chunks: Uint8Array[] = [];
      let size = 0;
      for await (const bytes of this.bytes(response, controller)) {
        size += bytes.length;
        if (size > this.maxBytes)
          throw new SdkError("LIMIT_EXCEEDED", "Response exceeds limit");
        chunks.push(bytes);
      }
      const text = Buffer.concat(chunks).toString("utf8");
      return this.unwrap<T>(response, text ? decode(text) : null, options.raw);
    } finally {
      controller.abort();
      finish();
    }
  }
  async *stream<T = unknown>(
    path: string,
    options: RequestOptions = {},
  ): AsyncGenerator<T> {
    const { response, controller, finish } = await this.open(path, {
      ...options,
      headers: { Accept: "application/x-ndjson", ...options.headers },
    });
    try {
      if (!response.ok)
        throw new SdkError(
          "REMOTE_ERROR",
          "Stream request failed",
          response.status,
        );
      let pending = Buffer.alloc(0);
      for await (const bytes of this.bytes(response, controller)) {
        pending = Buffer.concat([pending, bytes]);
        let pos;
        while ((pos = pending.indexOf(10)) >= 0) {
          if (pos > this.maxBytes)
            throw new SdkError("LIMIT_EXCEEDED", "NDJSON line exceeds limit");
          const line = pending.subarray(0, pos).toString("utf8").trim();
          pending = pending.subarray(pos + 1);
          if (line) yield decode<T>(line);
        }
        if (pending.length > this.maxBytes)
          throw new SdkError("LIMIT_EXCEEDED", "NDJSON line exceeds limit");
      }
      if (pending.toString().trim()) yield decode<T>(pending.toString());
    } finally {
      controller.abort();
      finish();
    }
  }
  async *pages<T = unknown>(
    path: string,
    query: Query = {},
  ): AsyncGenerator<T> {
    const size = query.pageSize ?? 100;
    if (size < 1 || size > 1000)
      throw new SdkError("INVALID_ARGUMENT", "Invalid page size");
    for (let index = query.pageIndex ?? 0; ; index++) {
      const page = await this.request<Page<T>>(path, {
        method: "POST",
        body: { ...query, pageSize: size, pageIndex: index },
      });
      if (!page || !Array.isArray(page.data) || page.data.length > size)
        throw new SdkError("SERIALIZATION", "Invalid page response");
      yield* page.data;
      if (!page.data.length || (index + 1) * size >= page.total) return;
    }
  }
  authorizationContext(): Promise<AuthorizationContext> {
    return this.request("/authorize/context");
  }
  businessApplication(id: string): BusinessApplications {
    return new BusinessApplications(this, id);
  }
  subscriptions(id: string): Subscriptions {
    return new Subscriptions(this, id);
  }
  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (!this.owner) return;
    this.shared.closed = true;
    for (const controller of this.shared.controllers) controller.abort();
    this.shared.controllers.clear();
    void this.shared.transport?.destroy().catch(() => {});
    this.owned?.close();
    if (typeof this.tokenProvider !== "string")
      this.tokenProvider.invalidate?.();
  }
}
export interface Authorization {
  url: string;
  state: string;
  verifier: string;
  redirectUri: string;
}
export interface OAuth2Token {
  access_token: string;
  token_type?: string;
  expires_in: number;
}
export class OAuth2 {
  private readonly transport: JetLinksClient;
  constructor(
    readonly baseUrl: string,
    private readonly key: string,
    private readonly secret: string,
    private readonly basic = true,
  ) {
    this.transport = new JetLinksClient(baseUrl);
  }
  private async token(form: Record<string, string>): Promise<OAuth2Token> {
    const headers: Record<string, string> = {
      "Content-Type": "application/x-www-form-urlencoded",
    };
    if (this.basic)
      headers.Authorization =
        "Basic " + Buffer.from(this.key + ":" + this.secret).toString("base64");
    else
      Object.assign(form, { client_id: this.key, client_secret: this.secret });
    const value = await this.transport.request<OAuth2Token>("/oauth2/token", {
      method: "POST",
      body: new URLSearchParams(form).toString(),
      headers,
      raw: true,
    });
    if (!value.access_token || value.expires_in <= 0)
      throw new SdkError("SERIALIZATION", "Invalid OAuth2 response");
    return value;
  }
  machine(scopes: string[] | null = null): Promise<OAuth2Token> {
    return this.token({
      grant_type: "client_credentials",
      ...(scopes !== null && { scope: scopes.join(" ") }),
    });
  }
  exchange(
    code: string,
    redirectUri: string,
    verifier?: string,
  ): Promise<OAuth2Token> {
    return this.token({
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri,
      ...(verifier && { code_verifier: verifier }),
    });
  }
  machineTokens(scopes: string[] | null = null): CachedTokenProvider {
    return new CachedTokenProvider(async () => {
      const value = await this.machine(scopes);
      return {
        token: value.access_token,
        expiresAt: Date.now() + value.expires_in * 1000,
      };
    }, 10000);
  }
  authorization(
    redirectUri: string,
    scopes: string[] | null = null,
  ): Authorization {
    const state = crypto.randomBytes(32).toString("base64url"),
      verifier = crypto.randomBytes(32).toString("base64url");
    return {
      state,
      verifier,
      redirectUri,
      url: target(this.baseUrl, "/oauth2/authorize", {
        response_type: "code",
        client_id: this.key,
        redirect_uri: redirectUri,
        state,
        code_challenge: crypto
          .createHash("sha256")
          .update(verifier)
          .digest("base64url"),
        code_challenge_method: "S256",
        ...(scopes !== null && { scope: scopes.join(" ") }),
      }),
    };
  }
  close(): void {
    this.transport.close();
  }
}
/** 仅缓存连接，不保存第二份应用状态；每次操作传入持久层读到的当前快照。 */
export class ApplicationClients {
  private readonly entries = new Map<
    string,
    { fingerprint: string; client: JetLinksClient }
  >();
  private closed = false;
  constructor(private readonly limit = 128) {}
  get(
    namespace: string,
    state: ApplicationState,
    mode: "local" | "oauth2" | "bearer" = "oauth2",
    scopes: string[] | null = null,
    bearer = "",
  ): JetLinksClient {
    if (this.closed)
      throw new SdkError("INVALID_STATE", "Client manager closed");
    if (!["local", "oauth2", "bearer"].includes(mode))
      throw new SdkError("INVALID_ARGUMENT", "Invalid auth mode");
    if (!active(state)) {
      this.invalidate(namespace);
      throw new SdkError("INVALID_ARGUMENT", "Inactive application");
    }
    const sorted =
      scopes === null ? null : [...new Set(scopes.map((v) => v.trim()))].sort();
    const key = encode([
      namespace,
      state.platformId,
      state.applicationId,
      mode,
      sorted,
    ]);
    const fingerprint = sha256(
      encode([
        state.createdAt,
        state.apiBaseUrl,
        state.accessKey,
        state.accessSecret,
        state.keyRevision,
        bearer,
      ]),
    );
    const old = this.entries.get(key);
    if (old?.fingerprint === fingerprint) return old.client;
    if (!old && this.entries.size >= this.limit)
      throw new SdkError("LIMIT_EXCEEDED", "Client capacity reached");
    const client =
      mode === "local"
        ? JetLinksClient.local(
            state.apiBaseUrl!,
            state.accessKey!,
            state.accessSecret!,
            sorted,
          )
        : mode === "oauth2"
          ? JetLinksClient.oauth2(
              state.apiBaseUrl!,
              state.accessKey!,
              state.accessSecret!,
              sorted,
            )
          : new JetLinksClient(state.apiBaseUrl!, { token: bearer });
    old?.client.close();
    this.entries.set(key, { fingerprint, client });
    return client;
  }
  invalidate(namespace: string): void {
    for (const [key, entry] of this.entries)
      if (decode<unknown[]>(key)[0] === namespace) {
        entry.client.close();
        this.entries.delete(key);
      }
  }
  close(): void {
    this.closed = true;
    for (const entry of this.entries.values()) entry.client.close();
    this.entries.clear();
  }
}
export class BusinessApplications {
  private readonly id: string;
  constructor(
    private readonly client: JetLinksClient,
    id: string,
  ) {
    this.id = segment(id);
  }
  members(): AsyncGenerator<Member> {
    return this.client.pages(
      `/user/detail/business_application/${this.id}/_query`,
    );
  }
  roles(): AsyncGenerator<Role> {
    return this.client.pages("/role/_query", {
      terms: [
        {
          column: "applicationId",
          termType: "eq",
          value: decodeURIComponent(this.id),
        },
      ],
    });
  }
  userRoles(): AsyncGenerator<UserRole> {
    return this.client.pages(
      `/business-application/${this.id}/user-roles/_query`,
    );
  }
  grants(): AsyncGenerator<ApiGrant> {
    return this.client.pages(
      `/business-application/${this.id}/api-grants/_query`,
    );
  }
  roleAuthorization(role: string): AsyncGenerator<RoleAuthorization> {
    return this.client.pages(
      `/business-application/${this.id}/roles/${segment(role)}/authorization/_query`,
    );
  }
  roleMenus(role: string): AsyncGenerator<RoleMenu> {
    return this.client.pages(
      `/business-application/${this.id}/roles/${segment(role)}/menus/_query`,
    );
  }
  assets(role: string): Promise<AssetAccesses[]> {
    return this.client.request(`/role/${segment(role)}/asset-accesses`);
  }
  syncCredential(): Promise<unknown> {
    return this.client.request(
      `/business-application/${this.id}/credential/_sync`,
      { method: "POST" },
    );
  }
}
export class Subscriptions {
  private readonly base: string;
  constructor(
    private readonly client: JetLinksClient,
    id: string,
  ) {
    this.base = `/application/${segment(id)}/subscriptions`;
  }
  private call<T>(tail: string, method = "GET", body?: unknown): Promise<T> {
    return this.client.request(this.base + tail, { method, body });
  }
  query(query: Query = {}): Promise<Page<Subscription>> {
    return this.call("/_query", "POST", query);
  }
  get(id: string): Promise<Subscription> {
    return this.call("/" + segment(id));
  }
  create(body: SubscriptionConfig): Promise<string> {
    return this.call("", "POST", body);
  }
  update(id: string, body: SubscriptionConfig): Promise<number> {
    return this.call("/" + segment(id), "PUT", body);
  }
  enable(id: string, enable = true): Promise<number> {
    return this.call(
      "/" + segment(id) + (enable ? "/_enable" : "/_disable"),
      "POST",
    );
  }
  delete(id: string): Promise<number> {
    return this.call("/" + segment(id), "DELETE");
  }
  validate(body: SubscriptionConfig): Promise<SubscriptionValidation> {
    return this.call("/_validate", "POST", body);
  }
  events(): Promise<EventProvider[]> {
    return this.call("/events");
  }
  channels(): Promise<DeliveryChannel[]> {
    return this.call("/channels");
  }
  tasks(id: string, query: Query = {}): Promise<Page<DeliveryTask>> {
    return this.call("/" + segment(id) + "/tasks/_query", "POST", query);
  }
  records(id: string, query: Query = {}): Promise<Page<DeliveryRecord>> {
    return this.call("/" + segment(id) + "/records/_query", "POST", query);
  }
  payload(id: string, task: string, attempt: number): Promise<DeliveryPayload> {
    return this.call(
      `/${segment(id)}/tasks/${segment(task)}/attempts/${attempt}/payload`,
    );
  }
}
/** 并发刷新合并；失败短暂冷却且保留上次完整快照。 */
export class SnapshotSynchronizer<T> {
  current: readonly T[] = [];
  private inFlight?: Promise<readonly T[]>;
  private retryAt = 0;
  constructor(
    private readonly loader: () => AsyncIterable<T>,
    private readonly limit: number,
  ) {}
  refresh(): Promise<readonly T[]> {
    if (this.inFlight && (!this.retryAt || Date.now() < this.retryAt))
      return this.inFlight;
    this.retryAt = 0;
    this.inFlight = Promise.resolve().then(async () => {
      const result: T[] = [];
      try {
        for await (const value of this.loader()) {
          result.push(value);
          if (result.length > this.limit)
            throw new SdkError("LIMIT_EXCEEDED", "Snapshot exceeds limit");
        }
        this.current = Object.freeze(result);
        this.inFlight = undefined;
        return this.current;
      } catch (error) {
        this.retryAt = Date.now() + 1000;
        throw error;
      }
    });
    return this.inFlight;
  }
}
