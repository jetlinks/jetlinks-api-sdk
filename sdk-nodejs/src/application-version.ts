import { Decimal } from "decimal.js";
import { type ApplicationState } from "./models.js";
import { encode, sha256, SdkError } from "./protocol.js";

/** 只保留版本与摘要；状态时间和凭据版本独立单调，不能用字典序比较。 */
export class ApplicationVersion {
  readonly createdAt: Decimal;
  readonly updatedAt: Decimal;
  readonly keyRevision: Decimal;
  readonly deleted: boolean;
  readonly status: string;
  readonly credentials: string;
  readonly connection: string;
  constructor(state: ApplicationState) {
    const version = (value: number | Decimal): Decimal => {
      const result = new Decimal(value);
      if (
        !result.isInteger() ||
        result.isNegative() ||
        (typeof value === "number" && !Number.isSafeInteger(value))
      )
        throw new SdkError("INVALID_ARGUMENT", "Invalid application version");
      return result;
    };
    this.createdAt = version(state.createdAt);
    this.updatedAt = version(state.updatedAt);
    this.keyRevision = version(state.keyRevision);
    this.deleted = !version(state.deletedAt).isZero();
    this.status = sha256(
      encode([state.enabled, state.deletedAt, state.apiBaseUrl]),
    );
    this.credentials = sha256(encode([state.accessKey, state.accessSecret]));
    this.connection = sha256(
      encode([
        this.createdAt,
        state.apiBaseUrl,
        this.credentials,
        this.keyRevision,
      ]),
    );
  }
  check(previous?: ApplicationVersion): void {
    if (!previous || this.createdAt.gt(previous.createdAt)) return;
    if (
      this.createdAt.lt(previous.createdAt) ||
      this.updatedAt.lt(previous.updatedAt) ||
      this.keyRevision.lt(previous.keyRevision) ||
      (previous.deleted && !this.deleted) ||
      (this.updatedAt.eq(previous.updatedAt) &&
        this.status !== previous.status &&
        !(this.deleted && !previous.deleted)) ||
      (this.keyRevision.eq(previous.keyRevision) &&
        !this.deleted &&
        this.credentials !== previous.credentials)
    )
      throw new SdkError(
        "INVALID_STATE",
        "Stale or conflicting application snapshot",
      );
  }
}
