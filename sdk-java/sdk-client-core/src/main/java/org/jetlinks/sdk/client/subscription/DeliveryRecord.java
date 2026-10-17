package org.jetlinks.sdk.client.subscription;

/**
 * 单次投递尝试的诊断信息。
 *
 * @since 2.0
 */
public class DeliveryRecord {
    public String recordId,
            taskId,
            registrationId,
            eventId,
            eventType,
            provider,
            nodeId,
            result,
            code;
    public Integer attempt;
    public Long startedAt, completedAt, nextRetryTime;
}
