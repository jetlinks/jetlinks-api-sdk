package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.auth.CachedTokenProvider;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

class AssetScopeTest {
    @Test
    void actualWirePrecedenceViewsPaginationStreamsAndHelpers() {
        List<List<String>> seen = new CopyOnWriteArrayList<>();
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) -> {
                                    List<String> scopes =
                                            req.requestHeaders().getAll("X-Asset-Scope");
                                    seen.add(scopes);
                                    return req.receive()
                                            .aggregate()
                                            .asString()
                                            .defaultIfEmpty("{}")
                                            .flatMap(
                                                    body -> {
                                                        Object result = scopes;
                                                        if (req.uri().equals("/pages")
                                                                || req.uri().endsWith("/_query")) {
                                                            int index =
                                                                    Json.parse(
                                                                                    body.getBytes(
                                                                                            java.nio
                                                                                                    .charset
                                                                                                    .StandardCharsets
                                                                                                    .UTF_8))
                                                                            .path("pageIndex")
                                                                            .asInt();
                                                            result =
                                                                    Map.of(
                                                                            "data",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "id",
                                                                                            ""
                                                                                                    + index,
                                                                                            "scopes",
                                                                                            scopes)),
                                                                            "total",
                                                                            req.uri()
                                                                                            .equals(
                                                                                                    "/pages")
                                                                                    ? 2
                                                                                    : 1);
                                                        }
                                                        if (req.uri().endsWith("/events"))
                                                            result = List.of();
                                                        String json =
                                                                req.uri().equals("/stream")
                                                                        ? Json.tree(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "scopes",
                                                                                                                scopes))
                                                                                        .toString()
                                                                                + "\n"
                                                                        : Json.tree(
                                                                                        Map.of(
                                                                                                "status",
                                                                                                200,
                                                                                                "result",
                                                                                                result))
                                                                                .toString();
                                                        return res.sendString(Mono.just(json))
                                                                .then();
                                                    });
                                })
                        .bindNow();
        AtomicInteger refreshes = new AtomicInteger(), invalidates = new AtomicInteger();
        CachedTokenProvider tokens =
                new CachedTokenProvider(
                        () -> {
                            refreshes.incrementAndGet();
                            return Mono.just(
                                    new CachedTokenProvider.Issued(
                                            "shared", System.currentTimeMillis() + 60000));
                        },
                        Duration.ZERO);
        TokenProvider provider =
                new TokenProvider() {
                    public Mono<String> token() {
                        return tokens.token();
                    }

                    public void invalidate() {
                        invalidates.incrementAndGet();
                        tokens.invalidate();
                    }
                };
        JetLinksReactiveClient root =
                new JetLinksReactiveClient("http://127.0.0.1:" + server.port(), provider);
        try {
            AtomicReference<AssetScope> retained = new AtomicReference<>();
            JetLinksReactiveClient a =
                    root.assetScope(
                            s -> {
                                retained.set(s);
                                s.tenant("T").organization("A");
                            });
            retained.get().organization("mutated");
            JetLinksReactiveClient b = a.assetScope(s -> s.organization("B"));
            assertEquals(Json.tree(List.of()), root.request(ApiRequest.get("/echo")).block());
            assertEquals(
                    Json.tree(List.of("tenant:T", "org:A")),
                    a.request(ApiRequest.get("/echo")).block());
            assertEquals(
                    Json.tree(List.of("tenant:T", "org:final:Id")),
                    a.request(
                                    ApiRequest.get("/echo")
                                            .header("x-ASSET-scope", "org:raw")
                                            .assetScope(
                                                    s ->
                                                            s.organization("old")
                                                                    .scope("org", "final:Id")))
                            .block());
            assertEquals(
                    Json.tree(List.of("tenant:T", "org:raw")),
                    b.request(ApiRequest.get("/echo").header("X-Asset-Scope", "org:raw")).block());
            Flux.range(0, 20)
                    .flatMap(
                            i ->
                                    (i % 2 == 0 ? a : b)
                                            .request(ApiRequest.get("/echo"))
                                            .doOnNext(
                                                    value ->
                                                            assertEquals(
                                                                    Json.tree(
                                                                            List.of(
                                                                                    "tenant:T",
                                                                                    i % 2 == 0
                                                                                            ? "org:A"
                                                                                            : "org:B")),
                                                                    value)))
                    .blockLast();
            var pages = a.pages("/pages", new Query().page(0, 1)).collectList().block();
            assertEquals(2, pages.size());
            pages.forEach(
                    row ->
                            assertEquals(
                                    Json.tree(List.of("tenant:T", "org:A")), row.get("scopes")));
            assertEquals(
                    Json.tree(Map.of("scopes", List.of("tenant:T", "org:stream"))),
                    b.stream(ApiRequest.get("/stream").assetScope(s -> s.organization("stream")))
                            .single()
                            .block());
            new BusinessApplications(a, "app").members().collectList().block();
            new Subscriptions(a, "app").events().block();
            seen.subList(seen.size() - 2, seen.size())
                    .forEach(value -> assertEquals(List.of("tenant:T", "org:A"), value));
            assertEquals(1, refreshes.get());
            a.close();
            assertEquals(0, invalidates.get());
            assertClosed(() -> a.request(ApiRequest.get("/echo")).block());
            assertEquals(
                    Json.tree(List.of("tenant:T", "org:B")),
                    b.request(ApiRequest.get("/echo")).block());
            JetLinksClient sync = new JetLinksClient(root, Duration.ofSeconds(5));
            try (JetLinksClient view = sync.assetScope(s -> s.organization("sync"))) {
                assertEquals(Json.tree(List.of("org:sync")), view.request(ApiRequest.get("/echo")));
            }
            root.close();
            assertClosed(() -> b.request(ApiRequest.get("/echo")).block());
            assertClosed(() -> b.assetScope(s -> s.organization("C")));
            assertEquals(1, invalidates.get());
            assertEquals(1, refreshes.get());
        } finally {
            root.close();
            server.disposeNow();
        }
    }

    private static void assertClosed(org.junit.jupiter.api.function.Executable action) {
        assertEquals(
                SdkException.Code.INVALID_STATE, assertThrows(SdkException.class, action).code);
    }

    @Test
    void invalidScopeAndLegacyHeaders() {
        for (String id :
                Arrays.asList(
                        "",
                        " a",
                        "a ",
                        "a\n",
                        "a\u007f",
                        "\u00a0a",
                        "a\ufeff",
                        "x".repeat(257),
                        null))
            assertEquals(
                    SdkException.Code.INVALID_ARGUMENT,
                    assertThrows(SdkException.class, () -> new AssetScope().organization(id)).code);
        assertThrows(SdkException.class, () -> new AssetScope().scope("bad:type", "id"));
        assertThrows(SdkException.class, () -> new AssetScope().scope("x".repeat(65), "id"));
        AssetScope builder = new AssetScope();
        for (int i = 0; i < 16; i++) builder.scope("d" + i, "id");
        builder.scope("d0", "replacement");
        assertThrows(SdkException.class, () -> builder.scope("extra", "id"));
        assertEquals(
                List.of("org:raw"),
                ApiRequest.get("/echo")
                        .header("X-Asset-Scope", "org:raw")
                        .assetScopeHeaders(Map.of()));
        assertThrows(
                SdkException.class,
                () ->
                        ApiRequest.get("/echo")
                                .header("X-Asset-Scope", "missing-colon")
                                .assetScopeHeaders(Map.of()));
        ApiRequest request = ApiRequest.get("/echo").assetScope(s -> s.organization("A"));
        request.assetScope(s -> s.tenant("T"));
        assertEquals(List.of("org:A", "tenant:T"), request.assetScopeHeaders(Map.of()));
    }

    @Test
    void externalHttpDefaultsRemainPartOfTheIntersection() {
        DisposableServer server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) ->
                                        res.sendString(
                                                Mono.just(
                                                        Json.tree(
                                                                        Map.of(
                                                                                "result",
                                                                                req.requestHeaders()
                                                                                        .getAll(
                                                                                                "X-Asset-Scope")))
                                                                .toString())))
                        .bindNow();
        try (JetLinksReactiveClient root =
                new JetLinksReactiveClient(
                        "http://127.0.0.1:" + server.port(),
                        TokenProvider.anonymous(),
                        reactor.netty.http.client.HttpClient.create()
                                .headers(
                                        h -> {
                                            h.add("X-Asset-Scope", "custom:external");
                                            h.add("X-Asset-Scope", "org:external");
                                        }),
                        Duration.ofSeconds(5),
                        1024)) {
            assertEquals(
                    Json.tree(List.of("custom:external", "org:external")),
                    root.request(ApiRequest.get("/echo")).block());
            try (var view = root.assetScope(s -> s.organization("view"))) {
                assertEquals(
                        Json.tree(List.of("custom:external", "org:view")),
                        view.request(ApiRequest.get("/echo")).block());
            }
        } finally {
            server.disposeNow();
        }
    }
}
