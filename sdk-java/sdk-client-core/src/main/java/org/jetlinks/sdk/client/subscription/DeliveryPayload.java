package org.jetlinks.sdk.client.subscription;

import org.jetlinks.sdk.client.event.StructuredEvent;

import java.util.Map;

/**
 * 投递尝试的解析正文及原始 HTTP 字节，按敏感诊断数据管理。
 *
 * @since 2.0
 */
public class DeliveryPayload {
    public String recordId,
            taskId,
            registrationId,
            eventId,
            eventType,
            endpoint,
            method,
            rawBody,
            sha256;
    public Integer attempt;
    public Long capturedAt;
    public StructuredEvent<Map<String, Object>> body;
}
