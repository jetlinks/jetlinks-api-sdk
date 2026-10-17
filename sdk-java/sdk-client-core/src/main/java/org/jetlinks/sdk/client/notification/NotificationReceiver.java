package org.jetlinks.sdk.client.notification;

import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 组合验签和可靠受理；HTTP 适配器仅在持久化成功后返回成功响应。
 *
 * @since 2.0
 */
public final class NotificationReceiver {
    private final NotificationVerifier verifier;
    private final NotificationStore store;

    public NotificationReceiver(NotificationVerifier verifier, NotificationStore store) {
        this.verifier = verifier;
        this.store = store;
    }

    public Mono<Boolean> receive(Map<String, List<String>> headers, byte[] body) {
        return verifier.verify(headers, body).flatMap(store::accept);
    }
}
