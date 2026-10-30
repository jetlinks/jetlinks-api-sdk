import crypto from "node:crypto";
import { Decimal } from "decimal.js";
import { JetLinksClient } from "./client.js";
import { lp, segment, decrypt, SdkError } from "./protocol.js";

const VERSION = "1";
const USAGE = "credential.read";
const REQUEST_DOMAIN = "business-application-credential/request";
const MIN_RSA_KEY_BITS = 2048;
const MAX_ID_LENGTH = 64;
const NONCE_BYTES = 16;

export interface ApplicationCredential {
  accessKey: string;
  accessSecret: string;
  keyId: string;
  keyRevision: number | Decimal;
}

/** 模板私钥证明，不依赖通知或已有 AccessSecret；复用受限传输且不跟随重定向。 */
export class BusinessApplicationCredentials {
  private readonly client: JetLinksClient;
  private readonly key: crypto.KeyObject;
  constructor(
    baseUrl: string,
    private readonly keyId: string,
    privateKey: string,
  ) {
    try {
      this.key = crypto.createPrivateKey(privateKey);
    } catch (cause) {
      throw new SdkError(
        "CRYPTOGRAPHY",
        "Cannot parse RSA private key",
        0,
        undefined,
        { cause },
      );
    }
    if (
      !keyId?.trim() ||
      this.key.asymmetricKeyType !== "rsa" ||
      (this.key.asymmetricKeyDetails?.modulusLength ?? 0) < MIN_RSA_KEY_BITS
    )
      throw new SdkError("INVALID_ARGUMENT", "Invalid RSA credential identity");
    this.client = new JetLinksClient(baseUrl);
  }
  async read(
    templateId: string,
    applicationId: string,
    platformId = "",
  ): Promise<ApplicationCredential> {
    if (
      ![templateId, applicationId].every(
        (v) =>
          typeof v === "string" &&
          v.trim() === v &&
          v.length > 0 &&
          v.length <= MAX_ID_LENGTH,
      ) ||
      typeof platformId !== "string" ||
      !/^[!-~]{0,128}$/.test(platformId)
    )
      throw new SdkError(
        "INVALID_ARGUMENT",
        "Invalid credential request identity",
      );
    const effective = platformId || templateId;
    const nonce = crypto.randomBytes(NONCE_BYTES).toString("base64url");
    const timestamp = Date.now();
    const sign = crypto
      .sign(
        "sha256",
        lp([
          REQUEST_DOMAIN,
          "POST",
          VERSION,
          templateId,
          applicationId,
          effective,
          USAGE,
          nonce,
          String(timestamp),
        ]),
        {
          key: this.key,
          padding: crypto.constants.RSA_PKCS1_PSS_PADDING,
          saltLength: crypto.constants.RSA_PSS_SALTLEN_DIGEST,
        },
      )
      .toString("base64url");
    const value = await this.client.request<Record<string, unknown>>(
      `/business-application-template/${segment(templateId)}/${segment(applicationId)}/credential/_encrypted`,
      {
        method: "POST",
        body: {
          version: VERSION,
          platformId,
          usage: USAGE,
          nonce,
          timestamp,
          sign,
        },
      },
    );
    const expected = {
      version: VERSION,
      platformId: effective,
      templateId,
      applicationId,
      usage: USAGE,
      nonce,
      keyId: this.keyId,
    };
    if (
      !value ||
      Object.entries(expected).some(([key, wanted]) => value[key] !== wanted) ||
      typeof value.accessKey !== "string" ||
      !value.accessKey ||
      typeof value.encryptedAccessSecret !== "string" ||
      !(
        (typeof value.keyRevision === "number" &&
          Number.isSafeInteger(value.keyRevision) &&
          value.keyRevision > 0) ||
        (Decimal.isDecimal(value.keyRevision) &&
          value.keyRevision.isInteger() &&
          value.keyRevision.gt(0))
      )
    )
      throw new SdkError(
        "AUTHENTICATION",
        "Invalid credential response identity",
      );
    const accessSecret = decrypt(value.encryptedAccessSecret, this.key);
    if (!accessSecret)
      throw new SdkError("AUTHENTICATION", "Empty credential response");
    return {
      accessKey: value.accessKey,
      accessSecret,
      keyId: this.keyId,
      keyRevision: value.keyRevision,
    };
  }
  close(): void {
    this.client.close();
  }
}
