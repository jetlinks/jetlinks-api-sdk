package org.jetlinks.sdk.client.command;

import java.util.List;

/** 查询当前应用可用指标的绝对用量，metricIds 为空或省略时返回全部可用项。 @since 2.0 */
public class ReadTenantUsage {
    /** 完整 resource:metric 标识；不传租户或应用身份参数。 */
    public List<String> metricIds;
}
