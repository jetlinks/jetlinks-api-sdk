import { SdkError } from "./protocol.js";

export type AssetScopeConfigurer = (scope: AssetScope) => unknown;

/** 不同维度取交集，同维度后设置覆盖前设置；范围选择不授予权限。 */
export class AssetScope {
  private readonly values = new Map<string, string>();
  organization(id: string): this {
    return this.scope("org", id);
  }
  tenant(id: string): this {
    return this.scope("tenant", id);
  }
  scope(type: string, id: string): this {
    for (const [value, max] of [
      [type, 64],
      [id, 256],
    ] as const) {
      if (
        typeof value !== "string" ||
        !value ||
        value.length > max ||
        value.trim() !== value ||
        /[\u0000-\u001f\u007f-\u009f]/u.test(value)
      )
        throw new SdkError("INVALID_ARGUMENT", "Invalid asset scope");
    }
    if (
      type.includes(":") ||
      (!this.values.has(type) && this.values.size >= 16)
    )
      throw new SdkError("INVALID_ARGUMENT", "Invalid asset scope");
    this.values.set(type, id);
    return this;
  }
  /** 复制结果，配置回调保存的 builder 不会改变已创建的视图。 */
  snapshot(): ReadonlyMap<string, string> {
    return new Map(this.values);
  }
}

export function configureScope(
  defaults: ReadonlyMap<string, string>,
  action: AssetScopeConfigurer,
): ReadonlyMap<string, string> {
  const builder = new AssetScope();
  for (const [type, id] of defaults) builder.scope(type, id);
  if (typeof action !== "function")
    throw new SdkError("INVALID_ARGUMENT", "Expected asset scope callback");
  action(builder);
  return builder.snapshot();
}

export function scopeHeaders(
  defaults: ReadonlyMap<string, string>,
  headers: Record<string, string> = {},
  action?: AssetScopeConfigurer,
): Record<string, string | string[]> {
  const builder = new AssetScope();
  for (const [type, id] of defaults) builder.scope(type, id);
  const result: Record<string, string | string[]> = Object.create(null);
  for (const [name, value] of Object.entries(headers)) {
    if (name.toLowerCase() === "x-asset-scope") {
      const colon = value.indexOf(":");
      if (colon < 1)
        throw new SdkError("INVALID_ARGUMENT", "Invalid asset scope");
      builder.scope(value.slice(0, colon), value.slice(colon + 1));
    } else result[name.toLowerCase()] = value;
  }
  const values =
    action === undefined
      ? builder.snapshot()
      : configureScope(builder.snapshot(), action);
  if (values.size)
    result["x-asset-scope"] = [...values].map(([type, id]) => `${type}:${id}`);
  return result;
}
