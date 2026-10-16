package org.jetlinks.sdk.subscription;

/**
 * 公开的投递任务视图，不包含租约、凭据和内部载荷。
 *
 * @since 2.0
 */
public class DeliveryTask {
    public String id, registrationId, eventId, provider, state, resultCode, resultMessage, resetBy;
    public Integer attempt, retryAttempt;
    public Long retryStartedAt, createdAt, readyAt, completedAt, resetAt;
}
