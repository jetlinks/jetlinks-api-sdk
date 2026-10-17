package org.jetlinks.sdk.client.command;

/** 平台标准命令标识；只有注册了实际处理器的命令才应出现在能力清单中。 @since 2.0 */
public final class CommandIds {
    public static final String GET_SERVICE_HEALTH = "GetServiceHealth";
    public static final String GET_SERVICE_INFO = "GetServiceInfo";
    public static final String GET_TENANT_METRICS = "GetTenantMetrics";
    public static final String READ_TENANT_USAGE = "ReadTenantUsage";
    public static final String SAVE_TENANT_LIMITS = "SaveTenantLimits";

    private CommandIds() {}
}
