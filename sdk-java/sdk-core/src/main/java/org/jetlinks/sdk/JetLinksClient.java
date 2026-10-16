package org.jetlinks.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * Blocking facade over the reactive client. Streams must be closed; never call from a nonblocking
 * event loop. @since 2.0
 */
public final class JetLinksClient implements AutoCloseable {
    private final JetLinksReactiveClient reactive;
    private final Duration timeout;

    public JetLinksClient(String baseUrl, TokenProvider provider) {
        this(new JetLinksReactiveClient(baseUrl, provider));
    }

    public JetLinksClient(JetLinksReactiveClient reactive) {
        this(reactive, Duration.ofSeconds(35));
    }

    public JetLinksClient(JetLinksReactiveClient reactive, Duration timeout) {
        this.reactive = reactive;
        this.timeout = timeout;
    }

    private void blocking() {
        if (Schedulers.isInNonBlockingThread())
            throw new IllegalStateException("Use JetLinksReactiveClient on a nonblocking thread");
    }

    public JsonNode request(ApiRequest request) {
        blocking();
        return reactive.request(request).block(timeout);
    }

    public <T> T request(ApiRequest request, TypeRef<T> type) {
        blocking();
        return reactive.request(request, type).block(timeout);
    }

    public <T> T request(ApiRequest request, Class<T> type) {
        return request(request, TypeRef.of(type));
    }

    public <T> Stream<T> stream(ApiRequest request, TypeRef<T> type) {
        blocking();
        return reactive.stream(request, type).toStream(1);
    }

    public <T> Stream<T> stream(ApiRequest request, Class<T> type) {
        return stream(request, TypeRef.of(type));
    }

    public <T> Stream<T> pages(String path, Query query, TypeRef<T> type) {
        blocking();
        return reactive.pages(path, query, type).toStream(1);
    }

    public <T> Stream<T> pages(String path, Query query, Class<T> type) {
        return pages(path, query, TypeRef.of(type));
    }

    public Stream<JsonNode> stream(ApiRequest request) {
        blocking();
        return reactive.stream(request).toStream(1);
    }

    public Stream<JsonNode> pages(String path, Query query) {
        blocking();
        return reactive.pages(path, query).toStream(1);
    }

    public void close() {
        reactive.close();
    }
}
