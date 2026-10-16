package org.jetlinks.sdk;

import reactor.core.publisher.Mono;

/**
 * Authentication source bound to one server/application/purpose/scope. Never reused for another
 * identity. @since 2.0
 */
public interface TokenProvider {
    /**
     * Called on subscription. Empty means anonymous; errors abort the request. Implementations must
     * not block.
     */
    Mono<String> token();

    /** Invalidates cached credentials; must not replay business requests. */
    default void invalidate() {}

    static TokenProvider bearer(String token) {
        return () -> Mono.just(token);
    }

    static TokenProvider anonymous() {
        return Mono::empty;
    }
}
