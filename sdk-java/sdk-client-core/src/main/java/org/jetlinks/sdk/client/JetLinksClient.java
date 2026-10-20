package org.jetlinks.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetlinks.sdk.client.SdkException.Code;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 响应式客户端的同步门面；流使用后必须关闭。禁止在非阻塞事件循环线程调用。
 *
 * @since 2.0
 */
public final class JetLinksClient implements AutoCloseable {
    /** 使用本地签名机器 Token 完成普通接入。 */
    public static JetLinksClient local(String base, String key, String secret) {
        return new JetLinksClient(JetLinksReactiveClient.local(base, key, secret));
    }

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
            throw new SdkException(
                    Code.INVALID_STATE, "Use JetLinksReactiveClient on a nonblocking thread");
    }

    public JsonNode request(ApiRequest request) {
        blocking();
        return await(reactive.request(request));
    }

    /** 同步读取类型化结果，空响应返回 null；错误以原始运行时异常抛出。 */
    public <T> T request(ApiRequest request, TypeRef<T> type) {
        blocking();
        return await(reactive.request(request, type));
    }

    private <T> T await(Mono<T> result) {
        return result.timeout(
                        timeout,
                        Mono.error(
                                () ->
                                        new SdkException(
                                                Code.TRANSPORT,
                                                "Blocking request timed out",
                                                new TimeoutException(
                                                        "Blocking request deadline exceeded"))))
                .block();
    }

    public <T> T request(ApiRequest request, Class<T> type) {
        return request(request, TypeRef.of(type));
    }

    /** 逐条读取 NDJSON；必须用 try-with-resources 关闭 Stream，以传递取消信号。 */
    public <T> Stream<T> stream(ApiRequest request, TypeRef<T> type) {
        blocking();
        return reactive.stream(request, type).toStream(1);
    }

    public <T> Stream<T> stream(ApiRequest request, Class<T> type) {
        return stream(request, TypeRef.of(type));
    }

    /** 逐页按需读取；提前结束时也必须关闭 Stream。 */
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

    /** 关闭底层响应式客户端。 */
    public void close() {
        reactive.close();
    }
}
