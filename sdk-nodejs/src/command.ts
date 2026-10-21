import { active, type ApplicationState } from "./models.js";
import {
  Headers,
  SdkError,
  checkHeaders,
  decode,
  encode,
  header,
  matches,
  sign,
  type HeaderInput,
} from "./protocol.js";

type Handler<T> = (
  application: ApplicationState,
  input: T,
  signal: AbortSignal,
) => unknown | Promise<unknown> | AsyncIterable<unknown>;
interface Definition {
  stream: boolean;
  handler: Handler<unknown>;
  allowed: (application: ApplicationState) => boolean;
}
/** 验签失败由适配器返回 401；已验签请求的错误响应也会签名。流正文完整性依赖 HTTPS。 */
export class CommandService {
  private readonly definitions = new Map<string, Definition>();
  private readonly budgets = new Map<string, number>();
  inFlight = 0;
  onError: (failure: {
    platformId: string;
    applicationId: string;
    requestId: string;
    commandId: string;
    cause: unknown;
  }) => void = (failure) => console.error("JetLinks command failed", failure);
  constructor(
    private readonly resolve: (
      platform: string,
      application: string,
    ) => ApplicationState | undefined | Promise<ApplicationState | undefined>,
    private readonly concurrency = 8,
    private readonly timeout = 30000,
  ) {
    if (Math.min(concurrency, timeout) < 1)
      throw new SdkError("INVALID_ARGUMENT", "Invalid command limits");
  }
  register<T = Record<string, unknown>>(
    id: string,
    stream: boolean,
    handler: Handler<T>,
    allowed = (_state: ApplicationState) => true,
  ): this {
    if (!id || this.definitions.has(id))
      throw new SdkError("INVALID_ARGUMENT", "Duplicate or invalid command");
    this.definitions.set(id, {
      stream,
      handler: handler as Handler<unknown>,
      allowed,
    });
    return this;
  }
  capabilities(application: ApplicationState): string[] {
    return [...this.definitions]
      .filter(([, d]) => d.allowed(application))
      .map(([id]) => id)
      .sort();
  }
  async handle(
    method: string,
    target: string,
    headers: HeaderInput,
    body: Uint8Array,
  ): Promise<Response> {
    checkHeaders(headers);
    const platform = header(headers, Headers.PLATFORM),
      app = header(headers, Headers.APPLICATION),
      key = header(headers, Headers.KEY);
    const requestId = header(headers, Headers.REQUEST),
      signature = header(headers, Headers.SIGNATURE);
    const resolved = await this.resolve(platform, app);
    if (
      !resolved ||
      !active(resolved) ||
      resolved.platformId !== platform ||
      resolved.applicationId !== app ||
      resolved.accessKey !== key ||
      !/^[!-~]{1,128}$/.test(platform) ||
      !/^[a-f0-9]{32}$/.test(requestId)
    )
      throw new SdkError(
        "AUTHENTICATION",
        "Inactive or invalid command identity",
      );
    const state = { ...resolved };
    const expected = sign(state.accessSecret!, [
      "http-command",
      "1",
      "request",
      app,
      platform,
      key,
      header(headers, Headers.TIMESTAMP),
      requestId,
      method,
      target,
      header(headers, "Content-Type", false),
      header(headers, "Accept", false),
    ]);
    if (!matches(expected, signature))
      throw new SdkError("AUTHENTICATION", "Command signature rejected");
    const identity = encode([platform, app]),
      count = this.budgets.get(identity) ?? 0;
    const controller = new AbortController();
    const deadline = async <T>(value: Promise<T>): Promise<T> => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        return await Promise.race([
          value,
          new Promise<never>((_, reject) => {
            timer = setTimeout(() => {
              controller.abort();
              reject(new SdkError("TRANSPORT", "Command timed out"));
            }, this.timeout);
          }),
        ]);
      } finally {
        clearTimeout(timer);
      }
    };
    const report = (commandId: string, cause: unknown) => {
      try {
        this.onError({
          platformId: platform,
          applicationId: app,
          requestId,
          commandId,
          cause,
        });
      } catch {
        /* 诊断回调不改变响应 */
      }
    };
    let released = false,
      acquired = false;
    const release = () => {
      if (!released && acquired) {
        released = true;
        controller.abort();
        this.inFlight--;
        const n = (this.budgets.get(identity) ?? 1) - 1;
        if (n) this.budgets.set(identity, n);
        else this.budgets.delete(identity);
      }
    };
    const respond = (
      status: number,
      stream: boolean,
      data: unknown,
      commandId = "",
    ): Response => {
      const type = stream ? "application/x-ndjson" : "application/json",
        time = String(Date.now());
      const signed = {
        [Headers.VERSION]: "1",
        [Headers.PLATFORM]: platform,
        [Headers.APPLICATION]: app,
        [Headers.KEY]: key,
        [Headers.TIMESTAMP]: time,
        [Headers.REQUEST]: requestId,
        "Content-Type": type,
        [Headers.SIGNATURE]: sign(state.accessSecret!, [
          "http-command",
          "1",
          "response",
          app,
          platform,
          key,
          time,
          requestId,
          signature,
          String(status),
          type,
        ]),
      };
      if (!stream) {
        try {
          const raw = encode(data);
          if (Buffer.byteLength(raw) > 1048576)
            throw new SdkError(
              "LIMIT_EXCEEDED",
              "Command result exceeds limit",
            );
          return new Response(raw, { status, headers: signed });
        } finally {
          release();
        }
      }
      const iterator = (data as AsyncIterable<unknown>)[Symbol.asyncIterator]();
      return new Response(
        new ReadableStream<Uint8Array>({
          async pull(controller) {
            try {
              const result = await deadline(iterator.next());
              if (result.done) {
                controller.close();
                release();
              } else {
                const raw = encode(result.value);
                if (Buffer.byteLength(raw) > 1048576)
                  throw new SdkError(
                    "LIMIT_EXCEEDED",
                    "Command result exceeds limit",
                  );
                controller.enqueue(Buffer.from(raw + "\n"));
              }
            } catch (error) {
              report(commandId, error);
              controller.error(error);
              release();
              void iterator.return?.().catch(() => {});
            }
          },
          async cancel() {
            release();
            if (iterator.return) await deadline(iterator.return());
          },
        }),
        { status, headers: signed },
      );
    };
    if (count >= this.concurrency)
      return respond(429, false, { error: "Command failed" });
    if (this.budgets.size >= 1024 && !count)
      return respond(503, false, { error: "Command failed" });
    acquired = true;
    this.budgets.set(identity, count + 1);
    this.inFlight++;
    let commandId = "";
    try {
      const path = target.split("?")[0];
      if (method === "GET" && path === "/api/capabilities")
        return respond(200, false, [
          { version: "1", capabilities: this.capabilities(state) },
        ]);
      if (method !== "POST" || !path.startsWith("/api/commands/1/"))
        return respond(404, false, { error: "Command failed" });
      commandId = decodeURIComponent(path.slice("/api/commands/1/".length));
      const definition = this.definitions.get(commandId);
      if (!definition || !definition.allowed(state))
        return respond(404, false, { error: "Command failed" });
      const input = body.length
        ? decode<Record<string, unknown>>(Buffer.from(body).toString())
        : {};
      if (!input || Array.isArray(input) || typeof input !== "object")
        return respond(400, false, { error: "Command failed" });
      // 保留字段只能由可信快照覆盖；业务参数无法冒用其他应用。
      Object.assign(input, {
        applicationId: app,
        templateId: state.templateId,
      });
      const result = await deadline(
        Promise.resolve(
          definition.handler({ ...state }, input, controller.signal),
        ),
      );
      return respond(200, definition.stream, result, commandId);
    } catch (cause) {
      report(commandId, cause);
      return respond(
        cause instanceof SdkError && cause.code === "SERIALIZATION" ? 400 : 500,
        false,
        { error: "Command failed" },
      );
    }
  }
}
