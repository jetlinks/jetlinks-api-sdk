import { Decimal } from "decimal.js";
export { Decimal };
/** 超出 JS 安全整数范围的业务版本由精确 JSON 编解码保留为 Decimal。 */
export type Revision = number | Decimal;

/** 固定线上契约；扩展数据保留在原对象中，身份以验签上下文为准。 */
export interface ApplicationState {
  platformId: string;
  applicationId: string;
  templateId?: string;
  apiBaseUrl?: string;
  accessKey?: string;
  accessSecret?: string;
  createdAt: number;
  keyRevision: Revision;
  updatedAt: number;
  deletedAt: number;
  enabled: boolean;
}
export const active = (state: ApplicationState) =>
  state.enabled &&
  !state.deletedAt &&
  !!state.apiBaseUrl &&
  !!state.accessKey &&
  !!state.accessSecret;
export interface StructuredEvent<T = unknown> {
  id: string;
  type: string;
  subject?: string;
  occurredAt?: number;
  data: T;
  extensions?: Record<string, unknown>;
  [key: string]: unknown;
}
export interface ApplicationInfo {
  id: string;
  templateId: string;
  createTime?: number;
  apiBaseUrl?: string;
  state?: string;
  extensions?: Record<string, unknown>;
}
export interface EncryptedCredential {
  accessKey: string;
  keyRevision: Revision;
  keyId: string;
  encryptedAccessSecret: string;
}
export interface LifecycleData {
  application: ApplicationInfo;
  payload?: { credential?: EncryptedCredential };
}
export interface TrustedKey {
  type: "api" | "business";
  secret: string;
  applicationId?: string;
  platformId?: string;
  templateId?: string;
  channelId: string;
}
export interface VerifiedNotification<T = unknown> {
  platformId: string;
  applicationId: string;
  eventId: string;
  key: TrustedKey;
  event: StructuredEvent<T>;
  rawBody: Uint8Array;
  signatureHeaders: Record<string, string[]>;
}
export interface Page<T> {
  total: number;
  pageIndex: number;
  pageSize: number;
  data: T[];
}
export interface Query {
  pageIndex?: number;
  pageSize?: number;
  terms?: unknown[];
  [key: string]: unknown;
}
export interface AuthorizationContext {
  application?: { id: string };
  subject?: { id: string; type: string };
  [key: string]: unknown;
}
export interface Member {
  id: string;
  name?: string;
  [key: string]: unknown;
}
export interface Role {
  id: string;
  applicationId?: string;
  [key: string]: unknown;
}
export interface UserRole {
  userId: string;
  roleId: string;
  [key: string]: unknown;
}
export interface ApiGrant {
  id?: string;
  [key: string]: unknown;
}
export interface RoleAuthorization {
  id?: string;
  [key: string]: unknown;
}
export interface RoleMenu {
  id?: string;
  [key: string]: unknown;
}
export interface AssetAccesses {
  assetType: string;
  [key: string]: unknown;
}
export interface SubscriptionConfig {
  name: string;
  scopes: { eventTypes: string[]; configuration?: Record<string, unknown> }[];
  channelProvider: string;
  channelConfiguration: Record<string, unknown>;
  [key: string]: unknown;
}
export interface Subscription extends SubscriptionConfig {
  id: string;
  applicationId: string;
  state: string;
}
export interface SubscriptionValidation {
  valid: boolean;
  issues: unknown[];
}
export interface EventDefinition {
  id: string;
  name?: string;
  group?: string;
  description?: string;
  version?: string;
  schema?: Record<string, unknown>;
}
export interface EventProvider {
  id: string;
  name?: string;
  events: EventDefinition[];
}
export interface DeliveryChannel {
  id: string;
}
export const ChannelIds = Object.freeze({ HTTP_HOOK: "http-hook" });
export interface DeliveryTask {
  id: string;
  subscriptionId: string;
  state: string;
  [key: string]: unknown;
}
export interface DeliveryRecord {
  taskId: string;
  attempt: number;
  state: string;
  [key: string]: unknown;
}
export interface DeliveryPayload {
  body: unknown;
  rawBody: string;
  contentType: string;
}
export interface ServiceHealth {
  status: string;
  details?: Record<string, unknown>;
}
export interface ServiceInfo {
  name: string;
  version: string;
  description?: string;
}
export interface TenantMetric {
  id: string;
  name?: string;
  unit?: string;
  type?: string;
  limitType?: string;
}
export const ResourceTypes = Object.freeze({
  ENTITY: "entity",
  REQUEST: "request",
  TIMESERIES: "timeseries",
});
export const LimitTypes = Object.freeze({
  UNLIMITED: "unlimited",
  QUOTA: "quotaLimit",
  WINDOW_QUOTA: "quotaTimeLimit",
  CONCURRENCY: "concurrency",
  RATE: "rateLimit",
});
export interface ResourceCapability {
  resource: string;
  capabilities: string[];
}
export interface TenantResource {
  id: string;
  name?: string;
  metrics: TenantMetric[];
  capabilities: { id: string; name?: string }[];
}
export type DecimalValue = Decimal | number;
export interface TenantUsage {
  metricId: string;
  usage: DecimalValue;
  [key: string]: unknown;
}
export interface ReadTenantUsage {
  metricIds?: string[];
}
export interface SaveTenantLimits {
  revision: Revision;
  limits: {
    metricId: string;
    limit: DecimalValue;
    windowDurationMillis?: number;
  }[];
  capabilities: ResourceCapability[];
}
export const CommandIds = Object.freeze({
  GET_SERVICE_HEALTH: "GetServiceHealth",
  GET_SERVICE_INFO: "GetServiceInfo",
  GET_TENANT_RESOURCES: "GetTenantResources",
  READ_TENANT_USAGE: "ReadTenantUsage",
  SAVE_TENANT_LIMITS: "SaveTenantLimits",
});
export const EventTypes = Object.freeze({
  APPLICATION_CREATED: "application.created",
  APPLICATION_UPDATED: "application.updated",
  APPLICATION_DELETED: "application.deleted",
  APPLICATION_ENABLED: "application.enabled",
  APPLICATION_DISABLED: "application.disabled",
  APPLICATION_CREDENTIAL_SYNCED: "application.credential.synced",
  APPLICATION_CREDENTIAL_ROTATED: "application.credential.rotated",
  APPLICATION_USER_BOUND: "application.user.bound",
  APPLICATION_USER_UNBOUND: "application.user.unbound",
  APPLICATION_SYNC_CHANGED: "application.sync.changed",
});
