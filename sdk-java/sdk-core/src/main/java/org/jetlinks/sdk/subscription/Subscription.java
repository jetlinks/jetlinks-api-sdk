package org.jetlinks.sdk.subscription;

/** Subscription management view, including validation and local execution state. @since 2.0 */
public class Subscription extends SubscriptionConfig {
    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";
    public String id, applicationId, state, creatorId, modifierId, executionState;
    public Long createTime, modifyTime;
    public SubscriptionValidation validation;
}
