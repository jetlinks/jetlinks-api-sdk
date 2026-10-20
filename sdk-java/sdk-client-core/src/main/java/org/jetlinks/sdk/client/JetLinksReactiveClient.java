package org.jetlinks.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;

import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.auth.OAuth2;
import org.jetlinks.sdk.client.internal.*;
import org.jetlinks.sdk.client.model.Page;

import reactor.core.publisher.*;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 绑定单一可信地址和认证上下文的非阻塞客户端；默认管理自己的连接池，外部注入的连接池由调用方关闭。
 *
 * @since 2.0
 */
public final class JetLinksReactiveClient implements AutoCloseable {
    /** 使用本地签名机器 Token，无需自行装配认证缓存；有效期须与平台一致。 */
    public static JetLinksReactiveClient local(String base, String key, String secret) {
        return new JetLinksReactiveClient(
                base, OAuth2.localTokens(key, secret, null, Duration.ofMinutes(2)));
    }

    private final String base;
    private final TokenProvider auth;
    private final HttpClient http;
    private final ConnectionProvider owned;
    private final Duration timeout;
    private final int maxBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final JetLinksReactiveClient root;
    private final Map<String, String> assetScopes;

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
        this.root = this;
        this.assetScopes = Collections.emptyMap();
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

    private JetLinksReactiveClient(JetLinksReactiveClient source, Map<String, String> scopes) {
        base = source.base;
        auth = source.auth;
        http = source.http;
        owned = null;
        timeout = source.timeout;
        maxBytes = source.maxBytes;
        root = source.root;
        assetScopes = scopes;
    }

    /** 返回共享认证和连接的独立范围视图；关闭视图不关闭根客户端。 */
    public JetLinksReactiveClient assetScope(Consumer<AssetScope> action) {
        ensureOpen();
        return new JetLinksReactiveClient(this, AssetScope.configure(assetScopes, action));
    }

    private void ensureOpen() {
        if (closed.get() || root.closed.get())
            throw new SdkException(Code.INVALID_STATE, "Client closed");
    }

    private HttpClient.RequestSender sender(ApiRequest request, String token) {
        ensureOpen();
        return http.headers(
                        h -> {
                            List<String> scopes =
                                    request.assetScopeHeaders(
                                            assetScopes, h.getAll(AssetScope.HEADER));
                            h.set("Accept", "application/json");
                            if (request.body != null) h.set("Content-Type", "application/json");
                            request.headers.forEach(h::set);
                            h.remove(AssetScope.HEADER);
                            scopes.forEach(value -> h.add(AssetScope.HEADER, value));
                            if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                        })
                .request(HttpMethod.valueOf(request.method))
                .uri(request.target(base));
    }

    /** 发送单次请求，保留原始状态和正文；Token 及网络操作在订阅时执行。 */
    public Mono<HttpResult> exchange(ApiRequest request) {
        return Mono.defer(
                        () -> {
                            ensureOpen();
                            return auth.token();
                        })
                .defaultIfEmpty("")
                .flatMap(token -> exchangeResponse(request, token))
                .timeout(timeout, Mono.error(JetLinksReactiveClient::requestTimeout));
    }

    private Mono<HttpResult> exchangeResponse(ApiRequest request, String token) {
        return Mono.defer(() -> send(request, token).response(this::collectResponse).single())
                .onErrorMap(JetLinksReactiveClient::transportFailure);
    }

    // 仅在 HTTP 边界包装传输错误，认证扩展在边界外执行，保留其原始异常。
    private static Throwable transportFailure(Throwable error) {
        return error instanceof SdkException
                ? error
                : new SdkException(Code.TRANSPORT, "HTTP transport failed", error);
    }

    private static SdkException requestTimeout() {
        return new SdkException(
                Code.TRANSPORT,
                "HTTP request timed out",
                new TimeoutException("HTTP request deadline exceeded"));
    }

    private HttpClient.ResponseReceiver<?> send(ApiRequest request, String token) {
        return sender(request, token)
                .send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(request.content())));
    }

    private Mono<HttpResult> collectResponse(HttpClientResponse response, ByteBufFlux body) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.responseHeaders()
                .names()
                .forEach(name -> headers.put(name, response.responseHeaders().getAll(name)));
        int status = response.status().code();
        return BoundedBytes.collect(body.asByteArray(), maxBytes)
                .map(bytes -> new HttpResult(status, headers, bytes));
    }

    public Mono<JsonNode> request(ApiRequest request) {
        return exchange(request).map(r -> r.value(request.unwrap));
    }

    /** 按指定泛型解码结果；JSON null 对应空 Mono。 */
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

    /** 按 NDJSON 逐条返回，取消订阅会中止 HTTP 响应读取。 */
    public Flux<JsonNode> stream(ApiRequest request) {
        return Mono.defer(
                        () -> {
                            ensureOpen();
                            return auth.token();
                        })
                .defaultIfEmpty("")
                .flatMapMany(token -> streamResponse(request, token))
                .timeout(timeout, Flux.error(JetLinksReactiveClient::requestTimeout));
    }

    private Flux<JsonNode> streamResponse(ApiRequest request, String token) {
        return Flux.defer(() -> send(request, token).response(this::decodeStream))
                .onErrorMap(JetLinksReactiveClient::transportFailure);
    }

    private Flux<JsonNode> decodeStream(HttpClientResponse response, ByteBufFlux body) {
        if (response.status().code() >= 400) {
            return collectResponse(response, body)
                    .flatMapMany(
                            result ->
                                    Flux.error(
                                            () -> new SdkException(result.status, result.json())));
        }
        // 逐行解码，不聚合整个流；解码器限制单行大小，取消信号继续传递给 HTTP 订阅。
        return Ndjson.decode(body.asByteArray(), maxBytes);
    }

    /** 按需逐页读取，每页受 pageSize 限制，不聚合整个数据集。 */
    public Flux<JsonNode> pages(String path, Query query) {
        return pages(path, query, JsonNode.class);
    }

    public <T> Flux<T> pages(String path, Query query, Class<T> type) {
        return pages(path, query, TypeRef.of(type));
    }

    /** 按需读取后续页，并逐条转换类型；调用期间不要修改 query。 */
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

    /** 使当前认证缓存失效，下一次请求重新获取 Token。 */
    public void invalidate() {
        auth.invalidate();
    }

    /** 关闭视图只阻止其后续调用；关闭根客户端还会失效共享认证并释放自建连接池。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && root == this) {
            auth.invalidate();
            if (owned != null) owned.dispose();
        }
    }
}
