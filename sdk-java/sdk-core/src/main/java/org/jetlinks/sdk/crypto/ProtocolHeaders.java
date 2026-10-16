package org.jetlinks.sdk.crypto;

import java.util.*;

/**
 * Reads signature fields without coalescing duplicates. Accepts HTTP adapters retaining all header
 * values. @since 2.0
 */
public final class ProtocolHeaders {
    public static final String SIGNATURE_VERSION = "X-Signature-Version";
    public static final String PLATFORM_ID = "X-Platform-Id";
    public static final String APPLICATION_ID = "X-Application-Id";
    public static final String KEY_ID = "X-Key-Id";
    public static final String TIMESTAMP = "X-Timestamp";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String SIGNATURE = "X-Signature";
    public static final String VERSION_1 = "1";

    private final Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public ProtocolHeaders(Map<String, List<String>> input) {
        input.forEach((k, v) -> values.computeIfAbsent(k, n -> new ArrayList<>()).addAll(v));
    }

    public String optional(String name) {
        List<String> v = values.get(name);
        if (v == null) return "";
        if (v.size() != 1 || v.get(0) == null || v.get(0).isEmpty())
            throw new SecurityException("Invalid signature header");
        return v.get(0);
    }

    public String required(String name) {
        String value = optional(name);
        if (value.isEmpty()) throw new SecurityException("Missing signature header");
        return value;
    }

    public long timestamp(long now, long window) {
        String value = required(TIMESTAMP);
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw new SecurityException("Invalid timestamp");
        long time = Long.parseLong(value);
        if (Math.abs(now - time) > window) throw new SecurityException("Expired signature");
        return time;
    }

    public void version() {
        if (!VERSION_1.equals(required(SIGNATURE_VERSION)))
            throw new SecurityException("Unsupported signature version");
    }
}
