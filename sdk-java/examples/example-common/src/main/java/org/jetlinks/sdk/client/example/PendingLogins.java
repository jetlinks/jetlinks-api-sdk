package org.jetlinks.sdk.client.example;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 登录等待记录的有界单次存取；清理、容量检查与预留共用一个原子边界。 */
final class PendingLogins<T> {
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final int limit;
    private final long lifetime;
    private final LongSupplier clock;

    PendingLogins(int limit, long lifetime, LongSupplier clock) {
        this.limit = limit;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    /** 容量仅计算未过期记录；返回 null 表示没有名额，不覆盖仍有效的登录。 */
    synchronized String add(T value) {
        long now = clock.getAsLong();
        entries.values().removeIf(entry -> entry.expires <= now);
        if (entries.size() >= limit) return null;
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry<>(value, now + lifetime));
        return id;
    }

    synchronized T take(String id) {
        Entry<T> entry = entries.remove(id);
        return entry == null || entry.expires <= clock.getAsLong() ? null : entry.value;
    }

    synchronized void discard(String id) {
        entries.remove(id);
    }

    private record Entry<T>(T value, long expires) {}
}
