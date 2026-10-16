package org.jetlinks.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.auth.*;
import org.junit.jupiter.api.*;

import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class HttpClientTest {
    @Test
    void gatewayTextErrorsRetainHttpStatusForBothResponseModes() {
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) ->
                                        res.status(502)
                                                .sendString(
                                                        Mono.just(
                                                                "<html>upstream"
                                                                        + " unavailable</html>")))
                        .bindNow();
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient(
                        "http://127.0.0.1:" + server.port(), TokenProvider.anonymous())) {
            SdkException failure =
                    assertThrows(
                            SdkException.class,
                            () -> client.request(ApiRequest.get("/failure")).block());
            assertEquals(502, failure.status);
            assertEquals(SdkException.Code.REMOTE_ERROR, failure.code);
            assertFalse(failure.getMessage().contains("upstream"));
            assertTrue(failure.response.isTextual());
            StepVerifier.create(client.stream(ApiRequest.get("/failure")))
                    .expectErrorMatches(
                            e -> e instanceof SdkException && ((SdkException) e).status == 502)
                    .verify();
        } finally {
            server.disposeNow();
        }
        assertThrows(SdkException.class, () -> Json.parse(new byte[0]));
    }

    @Test
    void failedTokenRefreshCoolsDownAndCountsActualRetries() {
        AtomicInteger attempts = new AtomicInteger();
        CachedTokenProvider provider =
                new CachedTokenProvider(
                        () ->
                                attempts.incrementAndGet() == 1
                                        ? Mono.error(() -> new IllegalStateException("unavailable"))
                                        : Mono.just(
                                                new CachedTokenProvider.Issued(
                                                        "recovered",
                                                        System.currentTimeMillis() + 60000)),
                        Duration.ZERO);
        StepVerifier.withVirtualTime(
                        () ->
                                provider.token()
                                        .onErrorResume(error -> Mono.empty())
                                        .then(provider.token().onErrorResume(error -> Mono.empty()))
                                        .then(Mono.delay(Duration.ofSeconds(2)))
                                        .then(provider.token()))
                .thenAwait(Duration.ofSeconds(2))
                .expectNext("recovered")
                .verifyComplete();
        assertEquals(2, attempts.get());
        assertEquals(2, provider.refreshCount());
    }

    @Test
    void realHttpPrefixGenericAndError() {
        AtomicInteger writes = new AtomicInteger();
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) -> {
                                    if (req.uri().equals("/gateway/fail")) {
                                        writes.incrementAndGet();
                                        return res.status(401)
                                                .sendString(
                                                        Mono.just(
                                                                "{\"status\":401,\"message\":\"denied\"}"));
                                    }
                                    return res.sendString(
                                            Mono.just("{\"result\":{\"items\":[1,2]}}"));
                                })
                        .bindNow();
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient(
                        "http://127.0.0.1:" + server.port() + "/gateway",
                        TokenProvider.anonymous())) {
            Map<String, List<Integer>> value =
                    new JetLinksClient(client)
                            .request(
                                    ApiRequest.get("/data"),
                                    new TypeRef<Map<String, List<Integer>>>() {});
            assertEquals(Arrays.asList(1, 2), value.get("items"));
            assertThrows(
                    SdkException.class,
                    () ->
                            new JetLinksClient(client)
                                    .request(
                                            ApiRequest.post(
                                                    "/fail", Collections.singletonMap("x", 1))));
            assertEquals(1, writes.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void concurrentMachineTokenRefreshAndInvalidation() {
        AtomicInteger tokens = new AtomicInteger();
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) ->
                                        res.sendString(
                                                Mono.delay(Duration.ofMillis(30))
                                                        .map(
                                                                i ->
                                                                        "{\"access_token\":\"token-"
                                                                                + tokens
                                                                                        .incrementAndGet()
                                                                                + "\",\"expires_in\":900}")))
                        .bindNow();
        try (OAuth2 oauth =
                new OAuth2("http://127.0.0.1:" + server.port(), "key", "secret", true)) {
            CachedTokenProvider cache = oauth.machineTokens(null);
            List<String> values =
                    Flux.range(0, 40).flatMap(i -> cache.token(), 40).collectList().block();
            assertEquals(40, values.size());
            assertEquals(1, tokens.get());
            cache.token().block();
            assertEquals(1, tokens.get());
            cache.invalidate();
            cache.token().block();
            assertEquals(2, tokens.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void ndjsonPreservesSplitUtf8AndCancels() {
        AtomicBoolean cancelled = new AtomicBoolean();
        byte[] line = "{\"message\":\"你好\"}\n".getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = new ArrayList<>();
        for (byte b : line) chunks.add(new byte[] {b});
        StepVerifier.create(
                        org.jetlinks.sdk.internal.Ndjson.decode(Flux.fromIterable(chunks), 1024))
                .assertNext(n -> assertEquals("你好", n.path("message").asText()))
                .verifyComplete();
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) ->
                                        res.header("Content-Type", "application/x-ndjson")
                                                .sendString(
                                                        Flux.interval(Duration.ofMillis(10))
                                                                .map(i -> "{\"i\":" + i + "}\n")
                                                                .doOnCancel(
                                                                        () -> cancelled.set(true))))
                        .bindNow();
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient(
                        "http://127.0.0.1:" + server.port(), TokenProvider.anonymous())) {
            StepVerifier.create(client.stream(ApiRequest.get("/stream")).take(2))
                    .expectNextCount(2)
                    .verifyComplete();
        } finally {
            server.disposeNow();
        }
        assertTrue(cancelled.get());
    }

    @Test
    void nonblockingThreadCannotUseBlockingFacade() {
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient("http://127.0.0.1:1", TokenProvider.anonymous())) {
            StepVerifier.create(
                            Mono.fromCallable(
                                            () ->
                                                    new JetLinksClient(client)
                                                            .request(ApiRequest.get("/")))
                                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
                    .expectErrorMatches(
                            error ->
                                    error instanceof SdkException
                                            && ((SdkException) error).code
                                                    == SdkException.Code.INVALID_STATE)
                    .verify();
        }
    }

    @Test
    void failedSnapshotRetainsPriorCompleteResult() {
        AtomicInteger calls = new AtomicInteger();
        SnapshotSynchronizer<Integer> sync =
                new SnapshotSynchronizer<>(
                        () ->
                                calls.incrementAndGet() == 1
                                        ? Flux.just(1, 2)
                                        : Flux.error(() -> new IllegalStateException("offline")),
                        10);
        assertEquals(Arrays.asList(1, 2), sync.refresh().block());
        assertThrows(IllegalStateException.class, () -> sync.refresh().block());
        assertEquals(Arrays.asList(1, 2), sync.current());
    }
}
