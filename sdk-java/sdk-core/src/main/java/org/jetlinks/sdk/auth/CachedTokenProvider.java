package org.jetlinks.sdk.auth;

import org.jetlinks.sdk.TokenProvider;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 每个身份和 Scope 维护一个 Token 缓存；并发刷新共用同一次订阅，不重放业务请求。
 *
 * @since 2.0
 */
public final class CachedTokenProvider implements TokenProvider {
    /**
     * Token 有效期由 OAuth2 expires_in 或本地签名的配置有效期决定。
     *
     * @since 2.0
     */
    public static final class Issued {
        public final String token;

        /** 绝对过期时间，单位为 Unix 毫秒。 */
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
        // cache() 使单次刷新不受调用方取消影响；代数检查阻止失效前的旧结果回写。
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

    /** 递增代数并清空缓存，阻止正在执行的旧刷新回写。 */
    public synchronized void invalidate() {
        generation++;
        cached = null;
        inFlight = null;
    }

    /** 实际发起加载的次数，包含失败；可供调用方接入监控。 */
    public long refreshCount() {
        return refreshes.get();
    }

    /** 直接使用有效缓存的次数。 */
    public long cacheHits() {
        return hits.get();
    }
}
