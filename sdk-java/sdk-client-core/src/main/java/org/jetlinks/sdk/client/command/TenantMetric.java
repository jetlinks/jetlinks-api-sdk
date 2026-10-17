package org.jetlinks.sdk.client.command;

/** 平台资源指标定义；id 为完整 resource:metric，unit 由资源业务约定。 @since 2.0 */
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
