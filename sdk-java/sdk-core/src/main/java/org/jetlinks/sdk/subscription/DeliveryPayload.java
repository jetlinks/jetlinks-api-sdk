package org.jetlinks.sdk.subscription;

import org.jetlinks.sdk.event.StructuredEvent;
import java.util.Map;

/** Captured attempt body and exact raw HTTP body; treat as sensitive diagnostic data. @since 2.0 */
public class DeliveryPayload {
    public String recordId, taskId, registrationId, eventId, eventType, endpoint, method, rawBody, sha256;
    public Integer attempt;
    public Long capturedAt;
    public StructuredEvent<Map<String, Object>> body;
}
