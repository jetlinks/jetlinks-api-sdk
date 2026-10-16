package org.jetlinks.sdk.client.notification;

import org.jetlinks.sdk.client.Json;
import org.jetlinks.sdk.client.TypeRef;
import org.jetlinks.sdk.client.event.StructuredEvent;

/**
 * 验签后的路由身份与收到的原始字节；event.subject/data 不用于选择接收方。
 *
 * @since 2.0
 */
public final class VerifiedNotification {
    public final String platformId, applicationId, eventId;
    public final TrustedKey key;
    public final StructuredEvent<java.util.Map<String, Object>> event;
    public final byte[] rawBody;
    public final java.util.Map<String, java.util.List<String>> signatureHeaders;

    VerifiedNotification(
            String platform,
            String app,
            String id,
            TrustedKey key,
            StructuredEvent<java.util.Map<String, Object>> event,
            byte[] raw,
            java.util.Map<String, java.util.List<String>> headers) {
        platformId = platform;
        applicationId = app;
        eventId = id;
        this.key = key;
        this.event = event;
        rawBody = raw.clone();
        signatureHeaders = new java.util.LinkedHashMap<>();
        headers.forEach(
                (k, v) -> {
                    if (k.toLowerCase(java.util.Locale.ROOT)
                            .matches(
                                    "x-(signature.*|platform-id|application-id|key-id|timestamp|event-id)"))
                        signatureHeaders.put(k, new java.util.ArrayList<>(v));
                });
    }

    /** 转换已知或自定义载荷类型；接收方身份始终使用验签 Header。 */
    public <T> T data(Class<T> type) {
        return Json.convert(event.data, type);
    }

    public <T> T data(TypeRef<T> type) {
        return Json.convert(event.data, type);
    }
}
