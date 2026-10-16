package org.jetlinks.sdk;

import org.jetlinks.sdk.SdkException.Code;

import reactor.core.publisher.*;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * 每个应用维护一个有界快照；并发触发共用一次加载，加载失败保留上次成功快照。
 *
 * @since 2.0
 */
public final class SnapshotSynchronizer<T> {
    private final Supplier<Flux<T>> loader;
    private final int limit;
    private final Clock clock;
    private volatile List<T> snapshot = Collections.emptyList();
    private Mono<List<T>> inFlight;
    private Object currentTask;
    private long retryAt;

    public SnapshotSynchronizer(Supplier<Flux<T>> loader, int limit) {
        this(loader, limit, Clock.systemUTC());
    }

    public SnapshotSynchronizer(Supplier<Flux<T>> loader, int limit, Clock clock) {
        this.loader = loader;
        this.limit = limit;
        this.clock = clock;
    }

    /** 触发或加入共享刷新；失败保留旧快照，并在短暂冷却后允许重试。 */
    public Mono<List<T>> refresh() {
        return Mono.defer(this::load);
    }

    private synchronized Mono<List<T>> load() {
        if (inFlight != null && (retryAt == 0 || clock.millis() < retryAt)) return inFlight;
        // 每次订阅重新选择共享任务，保存的 Mono 重订阅也遵循同一规则。
        // 一个任务只加载一次；失败在冷却期内复用，调用方取消不打断共享加载。
        Object task = new Object();
        currentTask = task;
        retryAt = 0;
        inFlight =
                Flux.defer(loader)
                        .take((long) limit + 1)
                        .collectList()
                        .map(this::publish)
                        .doOnSuccess(v -> clear(task))
                        .doOnError(error -> coolDown(task))
                        .cache();
        return inFlight;
    }

    private List<T> publish(List<T> values) {
        if (values.size() > limit)
            throw new SdkException(Code.LIMIT_EXCEEDED, "Snapshot exceeds limit");
        snapshot = Collections.unmodifiableList(new ArrayList<>(values));
        return snapshot;
    }

    private synchronized void clear(Object task) {
        if (currentTask == task) {
            inFlight = null;
            currentTask = null;
        }
    }

    private synchronized void coolDown(Object task) {
        if (currentTask == task) retryAt = clock.millis() + 1000;
    }

    /** 返回最后一次完整成功的只读快照，不触发加载。 */
    public List<T> current() {
        return snapshot;
    }
}
