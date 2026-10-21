package org.jetlinks.sdk.client.command;

import java.math.BigDecimal;
import java.util.List;

/**
 * 当前可信平台与应用的完整受管额度和资源能力快照，两个列表均须显式提供，省略的旧受管项撤销。 设置绝对上限并保留已有用量，不能用来充值、重置用量或删除业务数据。
 * 处理器须原子保存快照和版本；同版本幂等，旧版本不能覆盖，并返回当前版本。
 *
 * @since 2.0
 */
public class SaveTenantLimits {
    /** 由平台配额业务持久维护的单调版本，不使用请求 ID、密钥版本或时间戳代替。 */
    public Long revision;

    /** 必须显式提供完整列表，null 不表示撤销。 */
    public List<Limit> limits;

    /** 完整受管资源功能集合，空列表撤销全部；与 HTTP 命令能力清单无关。 */
    public List<ResourceCapability> capabilities;

    /** 单个指标的绝对额度配置。 @since 2.0 */
    public static class Limit {
        public String metricId;

        /** 0 禁止新增，负数不限额规范化为 -1；不表示当前用量。 */
        public BigDecimal limit;

        /** 平台规范化后的有效固定 Duration，毫秒；非周期指标省略。 */
        public Long windowDurationMillis;
    }

    /** 第三方已原子保存的配额版本，可能高于迟到请求的版本。 @since 2.0 */
    public static class Result {
        public long revision;

        public Result() {}

        public Result(long revision) {
            this.revision = revision;
        }
    }
}
