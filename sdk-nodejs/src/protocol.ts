import crypto from "node:crypto";
import { parse, stringify, LosslessNumber } from "lossless-json";
import { Decimal } from "decimal.js";

export type ErrorCode =
  | "INVALID_ARGUMENT"
  | "INVALID_STATE"
  | "AUTHENTICATION"
  | "SERIALIZATION"
  | "CRYPTOGRAPHY"
  | "LIMIT_EXCEEDED"
  | "REMOTE_ERROR"
  | "TRANSPORT";
/** 默认消息不带远端正文或凭据；response 仅供服务端显式诊断。 */
export class SdkError extends Error {
  constructor(
    public readonly code: ErrorCode,
    message: string,
    public readonly status = 0,
    public readonly response?: unknown,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}
export const Headers = Object.freeze({
  VERSION: "X-Signature-Version",
  PLATFORM: "X-Platform-Id",
  APPLICATION: "X-Application-Id",
  KEY: "X-Key-Id",
  TIMESTAMP: "X-Timestamp",
  EVENT: "X-Event-Id",
  REQUEST: "X-Request-Id",
  SIGNATURE: "X-Signature",
});
export type HeaderInput = Record<string, string | string[]>;
export function headerValues(input: HeaderInput): Record<string, string[]> {
  const result: Record<string, string[]> = {};
  for (const [name, values] of Object.entries(input))
    (result[name.toLowerCase()] ??= []).push(
      ...(Array.isArray(values) ? values : [values]),
    );
  return result;
}
export function header(
  input: HeaderInput,
  name: string,
  required = true,
): string {
  const values = headerValues(input)[name.toLowerCase()];
  if (!values && !required) return "";
  if (!values || values.length !== 1 || !values[0])
    throw new SdkError("AUTHENTICATION", "Invalid signature header");
  return values[0];
}
export function checkHeaders(input: HeaderInput, now = Date.now()): void {
  const time = header(input, Headers.TIMESTAMP);
  if (
    header(input, Headers.VERSION) !== "1" ||
    !/^(0|[1-9][0-9]{0,15})$/.test(time) ||
    Math.abs(now - Number(time)) > 300000
  )
    throw new SdkError("AUTHENTICATION", "Expired or unsupported signature");
}
export function lp(fields: string[]): Uint8Array {
  return Buffer.concat(
    fields.flatMap((value) => {
      const bytes = Buffer.from(value);
      const n = Buffer.alloc(4);
      n.writeUInt32BE(bytes.length);
      return [n, bytes];
    }),
  );
}
export const sign = (secret: string, fields: string[]) =>
  crypto.createHmac("sha256", secret).update(lp(fields)).digest("base64url");
export const sha256 = (bytes: Uint8Array | string) =>
  crypto.createHash("sha256").update(bytes).digest("hex");
export function matches(a: string, b: string): boolean {
  const first = Buffer.from(a),
    second = Buffer.from(b);
  return (
    first.length === second.length && crypto.timingSafeEqual(first, second)
  );
}
/** 精确小数在解析时即保留，不能在 JSON.parse 丢失精度后补救。 */
export function decode<T = unknown>(value: string): T {
  try {
    return parse(value, undefined, (value) => {
      const n = Number(value);
      return /^-?\d+$/.test(value) && Number.isSafeInteger(n)
        ? n
        : new Decimal(value);
    }) as T;
  } catch (cause) {
    throw new SdkError("SERIALIZATION", "Invalid JSON", 0, undefined, {
      cause,
    });
  }
}
export function encode(value: unknown): string {
  // Decimal.toJSON 会先变成字符串；先遍历转换为原始 JSON 数字，再交成熟 JSON 编码器。
  function prepare(v: unknown): unknown {
    if (Decimal.isDecimal(v)) {
      if (!v.isFinite())
        throw new SdkError("SERIALIZATION", "Non-finite decimal");
      return new LosslessNumber(v.toString());
    }
    if (typeof v === "bigint") return new LosslessNumber(v.toString());
    if (Array.isArray(v)) return v.map(prepare);
    if (v && typeof v === "object")
      return Object.fromEntries(
        Object.entries(v).map(([k, x]) => [k, prepare(x)]),
      );
    return v;
  }
  try {
    return stringify(prepare(value)) ?? "null";
  } catch (cause) {
    throw new SdkError("SERIALIZATION", "Cannot encode JSON", 0, undefined, {
      cause,
    });
  }
}
export function decrypt(ciphertext: string, privateKey: string): string {
  try {
    return crypto
      .privateDecrypt(
        {
          key: privateKey,
          oaepHash: "sha256",
          padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
        },
        Buffer.from(ciphertext, "base64"),
      )
      .toString();
  } catch (cause) {
    throw new SdkError(
      "CRYPTOGRAPHY",
      "Cannot decrypt credential",
      0,
      undefined,
      { cause },
    );
  }
}
/** 平台二进制 at_ Token；null Scope 表示默认权限，[] 表示无权限。 */
export function localToken(
  key: string,
  secret: string,
  scopes: string[] | null = null,
  issuedAt = Date.now(),
): string {
  if (!key || !secret || !Number.isSafeInteger(issuedAt) || issuedAt < 0)
    throw new SdkError("INVALID_ARGUMENT", "Invalid credential");
  const start = Buffer.alloc(11);
  start[0] = 11;
  start.writeBigInt64LE(BigInt(issuedAt), 1);
  start[9] = 1;
  start[10] = 2;
  const text = (value: string) => {
    const b = Buffer.from(value);
    if (b.length > 1024)
      throw new SdkError("LIMIT_EXCEEDED", "Token field too long");
    const n = Buffer.alloc(2);
    n.writeUInt16BE(b.length);
    return Buffer.concat([n, b]);
  };
  const sorted =
    scopes === null ? [] : [...new Set(scopes.map((v) => v.trim()))].sort();
  if (sorted.includes("") || sorted.length > 65535)
    throw new SdkError("INVALID_ARGUMENT", "Invalid scopes");
  const count = Buffer.alloc(3);
  count[0] = scopes === null ? 0 : 1;
  count.writeUInt16BE(sorted.length, 1);
  const bytes = Buffer.concat([
    start,
    text(key),
    count,
    ...sorted.map(text),
    text(""),
  ]);
  const derived = crypto
    .createHmac("sha256", secret)
    .update("application-access/self-signed/v1")
    .digest();
  const signature = crypto
    .createHmac("sha256", derived)
    .update(bytes)
    .digest()
    .subarray(0, 16);
  derived.fill(0);
  let number = BigInt("0x" + Buffer.concat([bytes, signature]).toString("hex")),
    result = "";
  const alphabet =
    "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  do {
    result = alphabet[Number(number % 62n)] + result;
    number /= 62n;
  } while (number);
  if (result.length + 3 > 4096)
    throw new SdkError("LIMIT_EXCEEDED", "Token exceeds limit");
  return "at_" + result;
}
export const segment = (value: string) => encodeURIComponent(value);
export function target(
  base: string,
  path: string,
  query: Record<string, string> = {},
): string {
  let url: URL;
  try {
    url = new URL(base);
  } catch {
    throw new SdkError("INVALID_ARGUMENT", "Invalid API base URL");
  }
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    !path.startsWith("/") ||
    path.startsWith("//") ||
    /[?#\\]/.test(path) ||
    path.split("/").some((v) => [".", ".."].includes(decodeURIComponent(v)))
  )
    throw new SdkError(
      "INVALID_ARGUMENT",
      "Expected trusted base and relative API path",
    );
  const suffix = new URLSearchParams(query).toString();
  return base.replace(/\/+$/, "") + path + (suffix ? "?" + suffix : "");
}
