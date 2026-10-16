package org.jetlinks.sdk.subscription;

/** One delivery attempt's diagnostic metadata. @since 2.0 */
public class DeliveryRecord {
    public String recordId, taskId, registrationId, eventId, eventType, provider, nodeId, result, code;
    public Integer attempt;
    public Long startedAt, completedAt, nextRetryTime;
}
