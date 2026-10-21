package org.jetlinks.sdk.client.command;

import java.util.List;

/** 应用声明的租户资源定义，不包含用量或已授予的额度、能力。 @since 2.0 */
public class TenantResource {
    /** 由提供方保证稳定且全局唯一，不要求注册为平台资产类型。 */
    public String id;

    public String name;
    public boolean assetResource;
    public List<TenantMetric> metrics;
    public List<Capability> capabilities;

    /** 资源功能定义，与 HTTP 命令能力清单无关。 @since 2.0 */
    public static class Capability {
        public String id, name;

        public Capability() {}

        public Capability(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
