package org.jetlinks.sdk.notification;

import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Composes verification with durable acceptance. HTTP adapters must ACK only after successful
 * completion. @since 2.0
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
