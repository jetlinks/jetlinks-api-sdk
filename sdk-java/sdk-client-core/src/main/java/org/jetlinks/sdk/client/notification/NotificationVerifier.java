package org.jetlinks.sdk.client.notification;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.crypto.*;
import org.jetlinks.sdk.client.event.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/**
 * 无状态 v1 通知验签；可信密钥归属仅来自本地配置或异步解析器。
 *
 * @since 2.0
 */
public final class NotificationVerifier {
    /**
     * 根据配置或已登记的归属查找密钥；返回空值或失败都会拒绝请求。实现必须非阻塞并限制查询开销。
     *
     * @since 2.0
     * @see TrustedKey
     */
    public interface KeyResolver {
        /**
         * 根据签名 Header 查询可信密钥；API 应用的 platformId 为空，业务应用必须匹配模版归属。
         *
         * @return 可信密钥，找不到时返回空 Mono，查询故障通过错误信号传播
         * @since 2.0
         */
        Mono<TrustedKey> resolve(String platformId, String applicationId, String keyId);
    }

    private final KeyResolver resolver;
    private final Clock clock;
    private final long window;

    public NotificationVerifier(KeyResolver resolver) {
        this(resolver, Clock.systemUTC(), Duration.ofMinutes(5));
    }

    public NotificationVerifier(KeyResolver resolver, Clock clock, Duration window) {
        this.resolver = resolver;
        this.clock = clock;
        this.window = window.toMillis();
    }

    /** 验证多值 Header、归属和原始字节签名后再解析事件；不负责持久化去重。 */
    public Mono<VerifiedNotification> verify(Map<String, List<String>> input, byte[] raw) {
        return Mono.defer(
                () -> {
                    ProtocolHeaders h = new ProtocolHeaders(input);
                    h.version();
                    h.timestamp(clock.millis(), window);
                    String platform = h.optional(ProtocolHeaders.PLATFORM_ID),
                            app = h.required(ProtocolHeaders.APPLICATION_ID),
                            keyId = h.required(ProtocolHeaders.KEY_ID),
                            id = h.required(ProtocolHeaders.EVENT_ID);
                    if (!app.equals(app.trim())
                            || (!platform.isEmpty() && !platform.matches("[!-~]{1,128}")))
                        throw new SdkException(Code.AUTHENTICATION, "Invalid application identity");
                    return resolver.resolve(platform, app, keyId)
                            .switchIfEmpty(
                                    Mono.error(
                                            () ->
                                                    new SdkException(
                                                            Code.AUTHENTICATION,
                                                            "Unknown notification key")))
                            .map(
                                    key -> {
                                        if (key.secret == null
                                                || key.secret.isEmpty()
                                                || key.applicationId != null
                                                        && !key.applicationId.equals(app))
                                            throw new SdkException(
                                                    Code.AUTHENTICATION, "Key ownership mismatch");
                                        if (TrustedKey.API.equals(key.type)) {
                                            if (!platform.isEmpty()
                                                    || !app.equals(key.applicationId))
                                                throw new SdkException(
                                                        Code.AUTHENTICATION,
                                                        "API key ownership mismatch");
                                        } else if (!TrustedKey.BUSINESS.equals(key.type)
                                                || platform.isEmpty()
                                                || !platform.equals(key.platformId)
                                                || key.templateId == null
                                                || key.templateId.isEmpty())
                                            throw new SdkException(
                                                    Code.AUTHENTICATION,
                                                    "Template key ownership mismatch");
                                        String expected =
                                                Signatures.sign(
                                                        key.secret,
                                                        ProtocolHeaders.VERSION_1,
                                                        app,
                                                        platform,
                                                        keyId,
                                                        h.required(ProtocolHeaders.TIMESTAMP),
                                                        id,
                                                        Signatures.sha256(raw));
                                        if (!Signatures.matches(
                                                expected, h.required(ProtocolHeaders.SIGNATURE)))
                                            throw new SdkException(
                                                    Code.AUTHENTICATION,
                                                    "Notification signature rejected");
                                        JsonNode document = Json.parse(raw);
                                        if (!document.isObject()
                                                || !document.path("id").isTextual()
                                                || !id.equals(document.path("id").textValue()))
                                            throw new SdkException(
                                                    Code.AUTHENTICATION, "Event ID mismatch");
                                        StructuredEvent<Map<String, Object>> event =
                                                Json.convert(
                                                        document,
                                                        new TypeRef<
                                                                StructuredEvent<
                                                                        Map<String, Object>>>() {});
                                        if (TrustedKey.BUSINESS.equals(key.type)) {
                                            BusinessApplicationEventData<Object> data =
                                                    Json.convert(
                                                            event.data,
                                                            new TypeRef<
                                                                    BusinessApplicationEventData<
                                                                            Object>>() {});
                                            ApplicationInfo application =
                                                    data == null ? null : data.application;
                                            if (application == null
                                                    || !app.equals(application.id)
                                                    || !key.templateId.equals(
                                                            application.templateId))
                                                throw new SdkException(
                                                        Code.AUTHENTICATION,
                                                        "Event ownership mismatch");
                                        }
                                        return new VerifiedNotification(
                                                platform, app, id, key, event, raw, input);
                                    });
                });
    }
}
