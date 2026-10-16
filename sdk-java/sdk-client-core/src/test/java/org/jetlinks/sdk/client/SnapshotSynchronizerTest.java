package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class SnapshotSynchronizerTest {
    @Test
    void reusedPublisherJoinsCurrentRefreshInsteadOfStartingAnotherLoad() {
        List<Sinks.One<Integer>> loads = new ArrayList<>();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () -> {
                            Sinks.One<Integer> source = Sinks.one();
                            loads.add(source);
                            return source.asMono().flux();
                        },
                        10);
        Mono<List<Integer>> reused = sync.refresh();
        Disposable first = reused.subscribe();
        loads.get(0).tryEmitValue(1);

        Disposable second = sync.refresh().subscribe();
        List<List<Integer>> repeatedResults = new ArrayList<>();
        Disposable repeated = reused.subscribe(repeatedResults::add);
        try {
            assertEquals(2, loads.size(), "Resubscription must join the pending refresh");
            loads.get(1).tryEmitValue(2);
            assertEquals(Arrays.asList(Arrays.asList(2)), repeatedResults);
            assertEquals(Arrays.asList(2), sync.current());
        } finally {
            first.dispose();
            second.dispose();
            repeated.dispose();
        }
    }

    @Test
    void subscriberSelectedBeforeCompletionCannotRestartAnOldTask() {
        List<Sinks.One<Integer>> loads = new ArrayList<>();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () -> {
                            Sinks.One<Integer> source = Sinks.one();
                            loads.add(source);
                            return source.asMono().flux();
                        },
                        10);
        List<Disposable> subscriptions = new ArrayList<>();
        List<List<Integer>> delayedResults = new ArrayList<>();
        try {
            subscriptions.add(
                    sync.refresh()
                            .doOnSubscribe(
                                    ignored -> {
                                        // Complete the task after this subscriber selects it but
                                        // before cache subscribes it.
                                        subscriptions.add(sync.refresh().subscribe());
                                        loads.get(0).tryEmitValue(1);
                                        subscriptions.add(sync.refresh().subscribe());
                                    })
                            .subscribe(delayedResults::add));
            assertEquals(2, loads.size(), "The completed task cannot execute again");
            assertEquals(Arrays.asList(Arrays.asList(1)), delayedResults);
            loads.get(1).tryEmitValue(2);
            assertEquals(Arrays.asList(2), sync.current());
        } finally {
            subscriptions.forEach(Disposable::dispose);
        }
    }

    @Test
    void successfulRefreshMayTriggerAnotherRefreshFromItsSubscriber() {
        Sinks.One<Integer> secondLoad = Sinks.one();
        AtomicInteger loads = new AtomicInteger();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () ->
                                loads.incrementAndGet() == 1
                                        ? Flux.just(1)
                                        : secondLoad.asMono().flux(),
                        10);
        Disposable chained = sync.refresh().flatMap(previous -> sync.refresh()).subscribe();
        Disposable joined = sync.refresh().subscribe();
        try {
            assertEquals(2, loads.get());
            secondLoad.tryEmitValue(2);
            assertEquals(Arrays.asList(2), sync.current());
        } finally {
            chained.dispose();
            joined.dispose();
        }
    }

    @Test
    void cancelledCallerDoesNotCancelTheSharedRefresh() {
        Sinks.One<Integer> source = Sinks.one();
        AtomicInteger loads = new AtomicInteger();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () -> {
                            loads.incrementAndGet();
                            return source.asMono().flux();
                        },
                        10);
        Disposable cancelled = sync.refresh().subscribe();
        cancelled.dispose();
        StepVerifier.create(sync.refresh())
                .then(() -> source.tryEmitValue(7))
                .expectNext(Arrays.asList(7))
                .verifyComplete();
        assertEquals(1, loads.get());
    }

    @Test
    void failedRefreshCoolsDownThenRetriesWithoutReplacingTheGoodSnapshot() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(1000);
        Clock clock =
                new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return Instant.ofEpochMilli(now.get());
                    }
                };
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () -> {
                            int attempt = loads.incrementAndGet();
                            return attempt == 1
                                    ? Flux.just(1)
                                    : attempt == 2
                                            ? Flux.error(() -> new IllegalStateException("offline"))
                                            : Flux.just(3);
                        },
                        10,
                        clock);
        assertEquals(Arrays.asList(1), sync.refresh().block());
        StepVerifier.create(sync.refresh()).expectError(IllegalStateException.class).verify();
        StepVerifier.create(sync.refresh()).expectError(IllegalStateException.class).verify();
        assertEquals(2, loads.get());
        assertEquals(Arrays.asList(1), sync.current());
        now.addAndGet(1000);
        assertEquals(Arrays.asList(3), sync.refresh().block());
        assertEquals(3, loads.get());
    }

    @Test
    void oversizedRefreshRetainsThePreviousSnapshot() {
        AtomicInteger loads = new AtomicInteger();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () -> loads.incrementAndGet() == 1 ? Flux.just(1) : Flux.range(1, 3), 2);
        assertEquals(Arrays.asList(1), sync.refresh().block());
        StepVerifier.create(sync.refresh())
                .expectErrorMatches(
                        error ->
                                error instanceof SdkException
                                        && ((SdkException) error).code
                                                == SdkException.Code.LIMIT_EXCEEDED)
                .verify();
        assertEquals(Arrays.asList(1), sync.current());
    }
}
