package org.jetlinks.sdk.client.subscription;

/**
 * 订阅管理视图，包含校验结果及执行状态。
 *
 * @since 2.0
 */
public class Subscription extends SubscriptionConfig {
    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";
    public String id, applicationId, state, creatorId, modifierId, executionState;
    public Long createTime, modifyTime;
    public SubscriptionValidation validation;
}
