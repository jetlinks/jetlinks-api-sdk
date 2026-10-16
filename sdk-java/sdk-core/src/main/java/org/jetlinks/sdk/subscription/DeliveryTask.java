package org.jetlinks.sdk.subscription;

/** Public task projection, excluding leases, credentials and internal payloads. @since 2.0 */
public class DeliveryTask {
    public String id, registrationId, eventId, provider, state, resultCode, resultMessage, resetBy;
    public Integer attempt, retryAttempt;
    public Long retryStartedAt, createdAt, readyAt, completedAt, resetAt;
}
