package org.jetlinks.sdk.notification;

import reactor.core.publisher.Mono;

/**
 * Atomic reliable-receipt boundary. Implement using a durable inbox and ownership/state
 * transaction. @since 2.0
 */
public interface NotificationStore {
    /**
     * Persist the verified event and any binding/state changes atomically. True=new, false=durable
     * duplicate. Called after verification; do not block event-loop threads. Failure propagates and
     * must cause non-2xx ACK. Business processing may follow durable acceptance; retries must not
     * repeat side effects.
     */
    Mono<Boolean> accept(VerifiedNotification notification);
}
