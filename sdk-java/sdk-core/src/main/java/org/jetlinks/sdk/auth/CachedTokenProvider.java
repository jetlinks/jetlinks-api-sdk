package org.jetlinks.sdk.auth;

import org.jetlinks.sdk.TokenProvider;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One bounded token slot per identity/scope; concurrent refresh shares one subscription. No
 * business request replay. @since 2.0
 */
public final class CachedTokenProvider implements TokenProvider {
    /** Token lifetime is provided by OAuth2 expires_in or the configured self-signed lifetime. */
    public static final class Issued {
        public final String token;
        public final long expiresAt;

        public Issued(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    private final Supplier<Mono<Issued>> loader;
    private final Clock clock;
    private final long skew;
    private Issued cached;
    private Mono<Issued> inFlight;
    private long generation;
    private final AtomicLong refreshes = new AtomicLong(), hits = new AtomicLong();

    public CachedTokenProvider(Supplier<Mono<Issued>> loader, Duration skew) {
        this(loader, skew, Clock.systemUTC());
    }

    public CachedTokenProvider(Supplier<Mono<Issued>> loader, Duration skew, Clock clock) {
        this.loader = loader;
        this.skew = skew.toMillis();
        this.clock = clock;
    }

    public Mono<String> token() {
        return Mono.defer(() -> load().map(i -> i.token));
    }

    private synchronized Mono<Issued> load() {
        if (cached != null && clock.millis() < cached.expiresAt - skew) {
            hits.incrementAndGet();
            return Mono.just(cached);
        }
        if (inFlight != null) return inFlight;
        long version = generation;
        // cache() keeps one refresh alive across caller cancellation; invalidate prevents a late
        // old value from being installed.
        inFlight =
                Mono.defer(
                                () -> {
                                    refreshes.incrementAndGet();
                                    return loader.get();
                                })
                        .doOnNext(
                                value -> {
                                    synchronized (this) {
                                        if (version == generation) cached = value;
                                    }
                                })
                        .doOnSuccess(value -> clear(version))
                        .cache(
                                value -> Duration.ZERO,
                                error -> Duration.ofSeconds(1),
                                () -> Duration.ZERO);
        return inFlight;
    }

    private synchronized void clear(long version) {
        if (version == generation) inFlight = null;
    }

    public synchronized void invalidate() {
        generation++;
        cached = null;
        inFlight = null;
    }

    public long refreshCount() {
        return refreshes.get();
    }

    public long cacheHits() {
        return hits.get();
    }
}
