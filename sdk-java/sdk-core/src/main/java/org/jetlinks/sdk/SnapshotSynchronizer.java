package org.jetlinks.sdk;

import reactor.core.publisher.*;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * One bounded snapshot per application; concurrent triggers share a reload and failed reloads
 * retain the prior snapshot. @since 2.0
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

    public Mono<List<T>> refresh() {
        return Mono.defer(this::load);
    }

    private synchronized Mono<List<T>> load() {
        if (inFlight != null && (retryAt == 0 || clock.millis() < retryAt)) return inFlight;
        // Resolve the shared task on every subscription, including resubscriptions of a saved Mono.
        // Each selected task executes once, even if a caller subscribes after its completion.
        // Failed tasks remain shared until the cooldown expires; cancellation cannot abort a reload.
        Object task = new Object();
        currentTask = task;
        retryAt = 0;
        inFlight = Flux.defer(loader)
                .take((long) limit + 1)
                .collectList()
                .map(this::publish)
                .doOnSuccess(v -> clear(task))
                .doOnError(error -> coolDown(task))
                .cache();
        return inFlight;
    }

    private List<T> publish(List<T> values) {
        if (values.size() > limit) throw new IllegalStateException("Snapshot exceeds limit");
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

    public List<T> current() {
        return snapshot;
    }
}
