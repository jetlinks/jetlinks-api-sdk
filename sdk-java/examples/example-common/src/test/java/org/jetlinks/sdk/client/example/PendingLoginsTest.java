package org.jetlinks.sdk.client.example;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

class PendingLoginsTest {
    private final AtomicLong clock = new AtomicLong(1000);

    @Test
    void reclaimsAllExpiredEntriesAtCapacityAndConsumesSuccessfulLoginOnce() {
        var pending = new PendingLogins<String>(128, 600000, clock::get);
        List<String> expired = new ArrayList<>();
        for (int i = 0; i < 128; i++) expired.add(pending.add("old"));
        assertNull(pending.add("blocked"));
        clock.addAndGet(600000);
        String id = pending.add("new");
        assertNotNull(id);
        expired.forEach(old -> assertNull(pending.take(old)));
        assertEquals("new", pending.take(id));
        assertNull(pending.take(id));
    }

    @Test
    void rejectsExpiredAndMissingCallbacks() {
        var pending = new PendingLogins<String>(1, 10, clock::get);
        String id = pending.add("expired");
        clock.addAndGet(10);
        assertNull(pending.take(id));
        assertNull(pending.take(null));
        assertNull(pending.take("unknown"));
        assertNotNull(pending.add("available"));
    }

    @Test
    void discardingAFailedLoginCannotRemoveAnotherReservation() {
        var pending = new PendingLogins<String>(1, 600000, clock::get);
        String old = pending.add("old");
        pending.discard(old);
        String current = pending.add("current");
        pending.discard(old);
        assertNull(pending.add("blocked"));
        assertEquals("current", pending.take(current));
    }

    @Test
    void concurrentReservationsKeepTheCapacityBound() throws Exception {
        var pending = new PendingLogins<Integer>(128, 600000, clock::get);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                int value = i;
                results.add(executor.submit(() -> pending.add(value)));
            }
            int accepted = 0;
            for (Future<String> result : results) if (result.get() != null) accepted++;
            assertEquals(128, accepted);
        } finally {
            executor.shutdownNow();
        }
    }
}
