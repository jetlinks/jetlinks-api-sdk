package org.jetlinks.sdk.client.command;

import java.math.BigDecimal;

/** 运行时绝对用量快照，非计费事实；真实零正常返回，缺失不编造零值。 @since 2.0 */
public class TenantUsage {
    public String metricId;
    public BigDecimal usage;

    /** 采样时间，epoch 毫秒。 */
    public long timestamp;

    /** 固定 Duration 窗口及其重置时间，非周期指标省略。 */
    public Long windowDurationMillis, nextResetTime;
}
