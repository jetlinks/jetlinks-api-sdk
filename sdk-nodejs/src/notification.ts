import {
  Decimal,
  active,
  EventTypes,
  type ApplicationState,
  type StructuredEvent,
  type LifecycleData,
  type TrustedKey,
  type VerifiedNotification,
} from "./models.js";
import {
  Headers,
  SdkError,
  checkHeaders,
  decode,
  decrypt,
  header,
  headerValues,
  matches,
  sha256,
  sign,
  target,
  type HeaderInput,
} from "./protocol.js";

export type KeyResolver = (
  platformId: string,
  applicationId: string,
  keyId: string,
) => TrustedKey | undefined | Promise<TrustedKey | undefined>;
/** 密钥归属仅来自本地可信配置；传入未经合并的多值 Header 和原始字节。 */
export class NotificationVerifier {
  constructor(private readonly resolve: KeyResolver) {}
  async verify(
    input: HeaderInput,
    rawBody: Uint8Array,
  ): Promise<VerifiedNotification> {
    checkHeaders(input);
    const platformId = header(input, Headers.PLATFORM, false),
      applicationId = header(input, Headers.APPLICATION);
    const keyId = header(input, Headers.KEY),
      eventId = header(input, Headers.EVENT);
    if (
      applicationId !== applicationId.trim() ||
      (platformId && !/^[!-~]{1,128}$/.test(platformId))
    )
      throw new SdkError("AUTHENTICATION", "Invalid application identity");
    const key = await this.resolve(platformId, applicationId, keyId);
    if (
      !key?.secret ||
      !["api", "business"].includes(key.type) ||
      (key.applicationId != null && key.applicationId !== applicationId) ||
      (key.type === "api"
        ? !!platformId || key.applicationId !== applicationId
        : !platformId || key.platformId !== platformId || !key.templateId)
    )
      throw new SdkError("AUTHENTICATION", "Unknown key or ownership mismatch");
    const expected = sign(key.secret, [
      "1",
      applicationId,
      platformId,
      keyId,
      header(input, Headers.TIMESTAMP),
      eventId,
      sha256(rawBody),
    ]);
    if (!matches(expected, header(input, Headers.SIGNATURE)))
      throw new SdkError("AUTHENTICATION", "Notification signature rejected");
    const event = decode<StructuredEvent<LifecycleData>>(
      Buffer.from(rawBody).toString("utf8"),
    );
    if (!event || event.id !== eventId)
      throw new SdkError("AUTHENTICATION", "Event ID mismatch");
    if (
      key.type === "business" &&
      (event.data?.application?.id !== applicationId ||
        event.data.application.templateId !== key.templateId)
    )
      throw new SdkError("AUTHENTICATION", "Event ownership mismatch");
    return {
      platformId,
      applicationId,
      eventId,
      key,
      event,
      rawBody: Uint8Array.from(rawBody),
      signatureHeaders: headerValues(input),
    };
  }
}
export interface NotificationStore {
  /** 同一事务保存 inbox、绑定及生命周期；已存在返回 false，持久化失败必须抛错。 */
  accept(notification: VerifiedNotification): Promise<boolean>;
}
export class NotificationReceiver {
  constructor(
    private readonly verifier: NotificationVerifier,
    private readonly store: NotificationStore,
  ) {}
  async receive(headers: HeaderInput, body: Uint8Array): Promise<boolean> {
    return this.store.accept(await this.verifier.verify(headers, body));
  }
}
/** 纯状态合并；返回值须与 inbox 在同一事务保存。删除只允许由更新的 createTime 开启新生命周期。 */
export function applyLifecycle(
  current: ApplicationState | undefined,
  notice: VerifiedNotification,
  privateKey: (keyId: string) => string,
): ApplicationState {
  const fresh = (): ApplicationState => ({
    platformId: notice.platformId,
    applicationId: notice.applicationId,
    templateId: notice.key.templateId,
    enabled: true,
    createdAt: 0,
    keyRevision: 0,
    updatedAt: 0,
    deletedAt: 0,
  });
  if (
    current &&
    (current.platformId !== notice.platformId ||
      current.applicationId !== notice.applicationId ||
      current.templateId !== notice.key.templateId)
  )
    throw new SdkError("AUTHENTICATION", "Existing ownership conflict");
  let state = current ? { ...current } : fresh();
  const event = notice.event as StructuredEvent<LifecycleData>;
  if (
    !Object.values(EventTypes).includes(
      event.type as (typeof EventTypes)[keyof typeof EventTypes],
    )
  )
    return state;
  const time = event.occurredAt,
    application = event.data.application,
    createdAt = application.createTime ?? 0;
  if (time == null || time < 0)
    throw new SdkError("INVALID_ARGUMENT", "Missing lifecycle occurredAt");
  if (createdAt < state.createdAt || time < createdAt) return state;
  if (createdAt > state.createdAt) {
    state = fresh();
    state.createdAt = createdAt;
  }
  if (event.type === EventTypes.APPLICATION_DELETED) {
    if (time >= state.updatedAt)
      Object.assign(state, {
        deletedAt: time,
        updatedAt: time,
        enabled: false,
        accessKey: undefined,
        accessSecret: undefined,
      });
    return state;
  }
  if (state.deletedAt) return state;
  if (time >= state.updatedAt) {
    if (application.apiBaseUrl != null) {
      target(application.apiBaseUrl, "/");
      state.apiBaseUrl = application.apiBaseUrl;
    }
    if (application.state != null)
      state.enabled = application.state === "enabled";
    if (event.type === EventTypes.APPLICATION_DISABLED) state.enabled = false;
    if (event.type === EventTypes.APPLICATION_ENABLED) state.enabled = true;
    state.updatedAt = time;
  }
  const credential = event.data.payload?.credential;
  if (
    [
      EventTypes.APPLICATION_CREATED,
      EventTypes.APPLICATION_CREDENTIAL_SYNCED,
      EventTypes.APPLICATION_CREDENTIAL_ROTATED,
    ].includes(event.type as typeof EventTypes.APPLICATION_CREATED) &&
    credential
  ) {
    if (
      (typeof credential.keyRevision !== "number" &&
        !Decimal.isDecimal(credential.keyRevision)) ||
      !new Decimal(credential.keyRevision).isInteger() ||
      new Decimal(credential.keyRevision).lt(1)
    )
      throw new SdkError("INVALID_ARGUMENT", "Invalid credential revision");
    if (
      !state.accessSecret ||
      new Decimal(credential.keyRevision).gt(state.keyRevision)
    ) {
      state.accessKey = credential.accessKey;
      state.accessSecret = decrypt(
        credential.encryptedAccessSecret,
        privateKey(credential.keyId),
      );
      state.keyRevision = credential.keyRevision;
    }
  }
  return state;
}
export { active };
