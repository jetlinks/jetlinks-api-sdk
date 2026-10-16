package org.jetlinks.sdk.notification;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetlinks.sdk.*;
import org.jetlinks.sdk.crypto.*;
import org.jetlinks.sdk.event.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/**
 * Stateless v1 notification verification. The asynchronous resolver is the sole source of trusted
 * key ownership. @since 2.0
 */
public final class NotificationVerifier {
    /**
     * Resolve from configured keys/registered ownership. Empty or error rejects; implementations
     * must be nonblocking and bounded.
     */
    public interface KeyResolver {
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
                        throw new SecurityException("Invalid application identity");
                    return resolver.resolve(platform, app, keyId)
                            .switchIfEmpty(
                                    Mono.error(new SecurityException("Unknown notification key")))
                            .map(
                                    key -> {
                                        if (key.secret == null
                                                || key.secret.isEmpty()
                                                || key.applicationId != null
                                                        && !key.applicationId.equals(app))
                                            throw new SecurityException("Key ownership mismatch");
                                        if (TrustedKey.API.equals(key.type)) {
                                            if (!platform.isEmpty()
                                                    || !app.equals(key.applicationId))
                                                throw new SecurityException(
                                                        "API key ownership mismatch");
                                        } else if (!TrustedKey.BUSINESS.equals(key.type)
                                                || platform.isEmpty()
                                                || !platform.equals(key.platformId)
                                                || key.templateId == null
                                                || key.templateId.isEmpty())
                                            throw new SecurityException(
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
                                            throw new SecurityException(
                                                    "Notification signature rejected");
                                        JsonNode document = Json.parse(raw);
                                        if (!document.isObject() || !document.path("id").isTextual()
                                                || !id.equals(document.path("id").textValue()))
                                            throw new SecurityException("Event ID mismatch");
                                        StructuredEvent<Map<String, Object>> event = Json.convert(
                                                document, new TypeRef<StructuredEvent<Map<String, Object>>>() {});
                                        if (TrustedKey.BUSINESS.equals(key.type)) {
                                            BusinessApplicationEventData<Object> data = Json.convert(
                                                    event.data, new TypeRef<BusinessApplicationEventData<Object>>() {});
                                            ApplicationInfo application = data == null ? null : data.application;
                                            if (application == null || !app.equals(application.id)
                                                    || !key.templateId.equals(application.templateId))
                                                throw new SecurityException(
                                                        "Event ownership mismatch");
                                        }
                                        return new VerifiedNotification(
                                                platform, app, id, key, event, raw, input);
                                    });
                });
    }
}
