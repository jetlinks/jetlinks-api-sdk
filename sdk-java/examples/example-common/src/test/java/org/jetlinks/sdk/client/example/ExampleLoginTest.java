package org.jetlinks.sdk.client.example;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.server.HttpServer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class ExampleLoginTest {
    @TempDir Path temp;

    private ExampleService service(String base, AtomicLong clock) {
        var config = new ExampleConfig();
        config.database = temp.resolve("login").toString();
        var app = new ExampleConfig.ApiApplication();
        app.contextId = "api";
        app.applicationId = "api-app";
        app.apiBaseUrl = base;
        app.accessKey = "synthetic-key";
        app.accessSecret = "synthetic-secret";
        app.userToken = "synthetic-user";
        config.apiApplications.add(app);
        return new ExampleService(config, clock::get);
    }

    private Mono<ExampleResponse> login(ExampleService service) {
        return service.handle(
                "POST",
                "/example-api/applications/api/login",
                Map.of(),
                Json.bytes(Map.of("entry", "third-party")));
    }

    @Test
    void anExpiredFullQueueAcceptsLoginAgain() {
        var platform =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (request, response) ->
                                        response.header("Content-Type", "application/json")
                                                .sendString(
                                                        Mono.just(
                                                                "{\"status\":200,\"result\":\"https://example.test/callback\"}")))
                        .bindNow();
        var clock = new AtomicLong(1000);
        try (var service = service("http://127.0.0.1:" + platform.port(), clock)) {
            for (int i = 0; i < 128; i++)
                assertEquals(200, login(service).block(Duration.ofSeconds(5)).status);
            assertEquals(429, login(service).block(Duration.ofSeconds(5)).status);
            clock.addAndGet(600000);
            assertEquals(200, login(service).block(Duration.ofSeconds(5)).status);
        } finally {
            platform.disposeNow();
        }
    }

    @Test
    void failedAuthorizationDoesNotConsumeLoginCapacity() {
        var failing = new AtomicBoolean(true);
        var platform =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (request, response) ->
                                        response.header("Content-Type", "application/json")
                                                .status(failing.get() ? 503 : 200)
                                                .sendString(
                                                        Mono.just(
                                                                failing.get()
                                                                        ? "{}"
                                                                        : "{\"status\":200,\"result\":\"https://example.test/callback\"}")))
                        .bindNow();
        try (var service = service("http://127.0.0.1:" + platform.port(), new AtomicLong(1000))) {
            for (int i = 0; i < 129; i++)
                assertEquals(503, login(service).block(Duration.ofSeconds(5)).status);
            failing.set(false);
            assertEquals(200, login(service).block(Duration.ofSeconds(5)).status);
        } finally {
            platform.disposeNow();
        }
    }

    @Test
    void canceledAuthorizationReleasesItsReservation() {
        var waiting = new AtomicBoolean(false);
        var received = Sinks.one();
        var platform =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (request, response) -> {
                                    if (waiting.get()) {
                                        received.tryEmitEmpty();
                                        return Mono.never();
                                    }
                                    return response.header("Content-Type", "application/json")
                                            .sendString(
                                                    Mono.just(
                                                            "{\"status\":200,\"result\":\"https://example.test/callback\"}"));
                                })
                        .bindNow();
        try (var service = service("http://127.0.0.1:" + platform.port(), new AtomicLong(1000))) {
            for (int i = 0; i < 127; i++)
                assertEquals(200, login(service).block(Duration.ofSeconds(5)).status);
            waiting.set(true);
            var canceled = login(service).subscribe();
            received.asMono().block(Duration.ofSeconds(5));
            canceled.dispose();
            waiting.set(false);
            assertEquals(200, login(service).block(Duration.ofSeconds(5)).status);
        } finally {
            platform.disposeNow();
        }
    }
}
