package org.jetlinks.sdk.client.command;

/** 资源内的指标定义；id 为局部标识，查询用量和保存额度时使用 resource:metric。 @since 2.0 */
public class TenantMetric {
    public enum ResourceType {
        entity,
        request,
        timeseries
    }

    public enum LimitType {
        unlimited,
        quotaLimit,
        quotaTimeLimit,
        concurrency,
        rateLimit
    }

    public String id, name, description, unit;
    public ResourceType type;
    public LimitType limitType;
}
