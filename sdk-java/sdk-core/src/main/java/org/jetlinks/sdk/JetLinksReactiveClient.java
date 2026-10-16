package org.jetlinks.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;

import org.jetlinks.sdk.internal.*;
import org.jetlinks.sdk.model.Page;

import reactor.core.publisher.*;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nonblocking client bound to one trusted origin/authentication context. Owns its connection pool
 * unless injected. @since 2.0
 */
public final class JetLinksReactiveClient implements AutoCloseable {
    private final String base;
    private final TokenProvider auth;
    private final HttpClient http;
    private final ConnectionProvider owned;
    private final Duration timeout;
    private final int maxBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JetLinksReactiveClient(String base, TokenProvider auth) {
        this(base, auth, null, Duration.ofSeconds(30), 1024 * 1024);
    }

    public JetLinksReactiveClient(
            String base, TokenProvider auth, HttpClient external, Duration timeout, int maxBytes) {
        ApiRequest.get("/").target(base);
        this.base = base;
        this.auth = Objects.requireNonNull(auth);
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        owned =
                external == null
                        ? ConnectionProvider.builder("jetlinks-sdk")
                                .maxConnections(32)
                                .pendingAcquireMaxCount(128)
                                .pendingAcquireTimeout(timeout)
                                .build()
                        : null;
        http =
                (external == null ? HttpClient.create(owned) : external)
                        .followRedirect(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                        .responseTimeout(timeout);
    }

    private HttpClient.RequestSender sender(ApiRequest request, String token) {
        if (closed.get()) throw new IllegalStateException("Client closed");
        return http.headers(
                        h -> {
                            h.set("Accept", "application/json");
                            if (request.body != null) h.set("Content-Type", "application/json");
                            request.headers.forEach(h::set);
                            if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                        })
                .request(HttpMethod.valueOf(request.method))
                .uri(request.target(base));
    }

    public Mono<HttpResult> exchange(ApiRequest request) {
        return Mono.defer(auth::token)
                .defaultIfEmpty("")
                .flatMap(token -> send(request, token).response(this::collectResponse).single())
                .timeout(timeout);
    }

    private HttpClient.ResponseReceiver<?> send(ApiRequest request, String token) {
        return sender(request, token)
                .send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(request.content())));
    }

    private Mono<HttpResult> collectResponse(HttpClientResponse response, ByteBufFlux body) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.responseHeaders().names().forEach(
                name -> headers.put(name, response.responseHeaders().getAll(name)));
        int status = response.status().code();
        return BoundedBytes.collect(body.asByteArray(), maxBytes)
                .map(bytes -> new HttpResult(status, headers, bytes));
    }

    public Mono<JsonNode> request(ApiRequest request) {
        return exchange(request).map(r -> r.value(request.unwrap));
    }

    public <T> Mono<T> request(ApiRequest request, TypeRef<T> type) {
        return request(request).mapNotNull(n -> Json.convert(n, type));
    }

    public <T> Mono<T> request(ApiRequest request, Class<T> type) {
        return request(request, TypeRef.of(type));
    }

    public <T> Flux<T> stream(ApiRequest request, TypeRef<T> type) {
        return stream(request).map(value -> Json.convert(value, type));
    }

    public <T> Flux<T> stream(ApiRequest request, Class<T> type) {
        return stream(request, TypeRef.of(type));
    }

    public Flux<JsonNode> stream(ApiRequest request) {
        return Mono.defer(auth::token)
                .defaultIfEmpty("")
                .flatMapMany(token -> send(request, token).response(this::decodeStream))
                .timeout(timeout);
    }

    private Flux<JsonNode> decodeStream(HttpClientResponse response, ByteBufFlux body) {
        if (response.status().code() >= 400) {
            return collectResponse(response, body)
                    .flatMapMany(result -> Flux.error(new SdkException(result.status, result.json())));
        }
        // Frame without aggregating the stream; both the per-line bound and cancellation remain
        // owned by the decoder and the HTTP subscription.
        return Ndjson.decode(body.asByteArray(), maxBytes);
    }

    /**
     * Fetches pages on demand. Each query result must be bounded by pageSize; no whole-dataset
     * collection.
     */
    public Flux<JsonNode> pages(String path, Query query) {
        return pages(path, query, JsonNode.class);
    }

    public <T> Flux<T> pages(String path, Query query, Class<T> type) {
        return pages(path, query, TypeRef.of(type));
    }

    public <T> Flux<T> pages(String path, Query query, TypeRef<T> type) {
        return Flux.defer(() -> page(path, query, query.pageIndex))
                .expand(result -> nextPage(path, query, result), 1)
                .concatMapIterable(page -> page.data)
                .map(value -> Json.convert(value, type));
    }

    private Mono<Page<JsonNode>> nextPage(String path, Query query, Page<JsonNode> result) {
        int next = result.pageIndex + 1;
        if (result.data.isEmpty() || (long) next * result.pageSize >= result.total)
            return Mono.empty();
        return page(path, query, next);
    }

    private Mono<Page<JsonNode>> page(String path, Query query, int index) {
        Query body = query.copy().page(index, query.pageSize);
        return request(ApiRequest.post(path, body), new TypeRef<Page<JsonNode>>() {})
                .map(
                        value -> {
                            value.pageIndex = index;
                            if (value.pageSize <= 0) value.pageSize = query.pageSize;
                            return value;
                        });
    }

    public String baseUrl() {
        return base;
    }

    public void invalidate() {
        auth.invalidate();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            auth.invalidate();
            if (owned != null) owned.dispose();
        }
    }
}
