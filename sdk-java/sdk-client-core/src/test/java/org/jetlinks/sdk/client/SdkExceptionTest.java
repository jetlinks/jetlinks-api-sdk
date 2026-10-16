package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.internal.BoundedBytes;
import org.jetlinks.sdk.client.internal.Ndjson;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

class SdkExceptionTest {
    @Test
    void jsonBoundariesRetainCategoryAndCause() {
        SdkException parse =
                assertThrows(
                        SdkException.class, () -> Json.parse("{".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Code.SERIALIZATION, parse.code);
        assertNotNull(parse.getCause());
        assertEquals(0, parse.status);
        assertNull(parse.response);

        assertEquals(
                Code.SERIALIZATION,
                assertThrows(SdkException.class, () -> Json.convert("invalid", Integer.class))
                        .code);
        assertEquals(
                Code.SERIALIZATION,
                assertThrows(
                                SdkException.class,
                                () -> Json.convert("invalid", new TypeRef<Integer>() {}))
                        .code);
        SdkException write = assertThrows(SdkException.class, () -> Json.bytes(new BrokenValue()));
        assertEquals(Code.SERIALIZATION, write.code);
        assertNotNull(write.getCause());
        assertEquals(
                Code.SERIALIZATION,
                assertThrows(SdkException.class, () -> Json.tree(new BrokenValue())).code);
        assertEquals(
                Code.SERIALIZATION,
                assertThrows(
                                SdkException.class,
                                () ->
                                        new HttpResult(
                                                        200,
                                                        Collections.emptyMap(),
                                                        Json.bytes(
                                                                Collections.singletonMap(
                                                                        "ok", true)))
                                                .value(true))
                        .code);
    }

    public static final class BrokenValue {
        public String getValue() {
            throw new IllegalStateException("caller serialization failure");
        }
    }

    @Test
    void localFailuresAndCapacityLimitsHaveStableCategories() {
        assertEquals(
                Code.INVALID_ARGUMENT,
                assertThrows(SdkException.class, () -> ApiRequest.get("//other.example")).code);
        assertEquals(
                Code.INVALID_ARGUMENT,
                assertThrows(SdkException.class, () -> ApiRequest.get("/").target("[invalid"))
                        .code);
        assertEquals(
                Code.CRYPTOGRAPHY,
                assertThrows(
                                SdkException.class,
                                () -> Signatures.decrypt("invalid", "invalid private key"))
                        .code);
        assertEquals(
                Code.LIMIT_EXCEEDED,
                assertThrows(
                                SdkException.class,
                                () -> BoundedBytes.collect(Flux.just(new byte[3]), 2).block())
                        .code);
        assertEquals(
                Code.LIMIT_EXCEEDED,
                assertThrows(
                                SdkException.class,
                                () ->
                                        Ndjson.decode(
                                                        Flux.just(
                                                                "123\n"
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)),
                                                        2)
                                                .blockLast())
                        .code);
    }

    @Test
    void closedClientFailsOnSubscriptionWithTheSameCategoryForBothModes() {
        JetLinksReactiveClient client =
                new JetLinksReactiveClient("http://localhost", TokenProvider.anonymous());
        client.close();
        Mono<?> request = client.request(ApiRequest.get("/"));
        SdkException first = assertThrows(SdkException.class, request::block);
        SdkException second = assertThrows(SdkException.class, request::block);
        assertEquals(Code.INVALID_STATE, first.code);
        assertNotSame(first, second);
        assertEquals(
                Code.INVALID_STATE,
                assertThrows(
                                SdkException.class,
                                () -> client.stream(ApiRequest.get("/")).blockLast())
                        .code);
    }

    @Test
    void synchronousDeadlineUsesUnifiedTransportFailure() {
        try (JetLinksReactiveClient reactive =
                        new JetLinksReactiveClient("http://localhost", Mono::never);
                JetLinksClient client = new JetLinksClient(reactive, Duration.ofMillis(20))) {
            SdkException failure =
                    assertThrows(SdkException.class, () -> client.request(ApiRequest.get("/")));
            assertEquals(Code.TRANSPORT, failure.code);
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
        }
    }

    @Test
    void transportFailuresAreUnifiedWithoutWrappingAuthenticationExtensions() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).bindNow();
        String base = "http://127.0.0.1:" + server.port();
        server.disposeNow();
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient(base, TokenProvider.anonymous())) {
            SdkException failure =
                    assertThrows(
                            SdkException.class, () -> client.request(ApiRequest.get("/")).block());
            assertEquals(Code.TRANSPORT, failure.code);
            assertNotNull(failure.getCause());
            assertEquals(
                    Code.TRANSPORT,
                    assertThrows(
                                    SdkException.class,
                                    () -> client.stream(ApiRequest.get("/")).blockLast())
                            .code);
        }
        IllegalStateException original = new IllegalStateException("custom authentication failed");
        try (JetLinksReactiveClient client =
                new JetLinksReactiveClient(base, () -> Mono.error(original))) {
            assertSame(
                    original,
                    assertThrows(
                            IllegalStateException.class,
                            () -> client.request(ApiRequest.get("/")).block()));
        }
    }
}
