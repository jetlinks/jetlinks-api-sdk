package org.jetlinks.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.command.*;
import org.jetlinks.sdk.crypto.*;
import org.jetlinks.sdk.notification.ApplicationState;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class CommandTest {
    ApplicationState state() {
        ApplicationState a = new ApplicationState();
        a.platformId = "region";
        a.applicationId = "app";
        a.templateId = "template";
        a.apiBaseUrl = "https://platform/api";
        a.accessKey = "key";
        a.accessSecret = "secret";
        return a;
    }

    Map<String, List<String>> headers(String method, String target) {
        String time = Long.toString(System.currentTimeMillis());
        Map<String, List<String>> h = new LinkedHashMap<>();
        String[]
                names =
                        {
                            "X-Signature-Version",
                            "X-Platform-Id",
                            "X-Application-Id",
                            "X-Key-Id",
                            "X-Timestamp",
                            "X-Request-Id",
                            "Content-Type",
                            "Accept",
                            "X-Signature"
                        },
                values =
                        {
                            "1",
                            "region",
                            "app",
                            "key",
                            time,
                            "0123456789abcdef0123456789abcdef",
                            "application/json",
                            "application/json",
                            Signatures.sign(
                                    "secret",
                                    "http-command",
                                    "1",
                                    "request",
                                    "app",
                                    "region",
                                    "key",
                                    time,
                                    "0123456789abcdef0123456789abcdef",
                                    method,
                                    target,
                                    "application/json",
                                    "application/json")
                        };
        for (int i = 0; i < names.length; i++)
            h.put(names[i], Collections.singletonList(values[i]));
        return h;
    }

    @Test
    void capabilityAndReservedIdentity() {
        CommandService service =
                new CommandService((p, a) -> Mono.just(state()), 1)
                        .register("Custom", false, (a, n) -> Mono.just(n));
        CommandService.Response caps =
                service.handle(
                                "GET",
                                "/api/capabilities",
                                headers("GET", "/api/capabilities"),
                                new byte[0])
                        .block();
        assertEquals(
                "Custom",
                Json.parse(caps.body.blockFirst()).get(0).path("capabilities").get(0).asText());
        caps.close();
        Map<String, List<String>> h = headers("POST", "/api/commands/1/Custom");
        CommandService.Response response =
                service.handle(
                                "POST",
                                "/api/commands/1/Custom",
                                h,
                                Json.bytes(Collections.singletonMap("applicationId", "spoof")))
                        .block();
        assertEquals("app", Json.parse(response.body.blockFirst()).path("applicationId").asText());
        assertEquals(0, service.inFlight());
        assertEquals(
                Signatures.sign(
                        "secret",
                        "http-command",
                        "1",
                        "response",
                        "app",
                        "region",
                        "key",
                        response.headers.get("X-Timestamp"),
                        "0123456789abcdef0123456789abcdef",
                        h.get("X-Signature").get(0),
                        "200",
                        "application/json"),
                response.headers.get("X-Signature"));
    }

    @Test
    void cancellationAndSignedBusyError() {
        CommandService service =
                new CommandService((p, a) -> Mono.just(state()), 1)
                        .register("Stream", true, (a, n) -> Flux.just(1).concatWith(Flux.never()));
        CommandService.Response stream =
                service.handle(
                                "POST",
                                "/api/commands/1/Stream",
                                headers("POST", "/api/commands/1/Stream"),
                                new byte[0])
                        .block();
        CommandService.Response busy =
                service.handle(
                                "GET",
                                "/api/capabilities",
                                headers("GET", "/api/capabilities"),
                                new byte[0])
                        .block();
        assertEquals(429, busy.status);
        assertNotNull(busy.headers.get("X-Signature"));
        StepVerifier.create(stream.body).expectNextCount(1).thenCancel().verify();
        assertEquals(0, service.inFlight());
    }

    private CommandService.Response hold(CommandService service, int index) {
        String target = "/api/commands/1/Hold", app = "app-" + index;
        Map<String, List<String>> h = headers("POST", target);
        h.put("X-Application-Id", Collections.singletonList(app));
        h.put(
                "X-Signature",
                Collections.singletonList(
                        Signatures.sign(
                                "secret",
                                "http-command",
                                "1",
                                "request",
                                app,
                                "region",
                                "key",
                                h.get("X-Timestamp").get(0),
                                h.get("X-Request-Id").get(0),
                                "POST",
                                target,
                                "application/json",
                                "application/json")));
        return service.handle("POST", target, h, new byte[0]).block();
    }

    @Test
    void boundedApplicationBudgetsEvictOnlyIdleIdentities() {
        CommandService service =
                new CommandService(
                                (platform, app) -> {
                                    ApplicationState value = state();
                                    value.applicationId = app;
                                    return Mono.just(value);
                                },
                                1)
                        .register("Hold", true, (a, n) -> Flux.never());
        List<CommandService.Response> held = new ArrayList<>();
        try {
            for (int i = 0; i < 1024; i++) {
                CommandService.Response r = hold(service, i);
                assertEquals(200, r.status);
                held.add(r);
            }
            try (CommandService.Response full = hold(service, 1024)) {
                assertEquals(503, full.status);
            }
            held.get(0).close();
            CommandService.Response replacement = hold(service, 1024);
            held.add(replacement);
            assertEquals(200, replacement.status);
            try (CommandService.Response busy = hold(service, 1)) {
                assertEquals(429, busy.status);
            }
            try (CommandService.Response busy = hold(service, 1024)) {
                assertEquals(429, busy.status);
            }
        } finally {
            held.forEach(CommandService.Response::close);
        }
        assertEquals(0, service.inFlight());
    }

    @Test
    void failuresAreSignedAndUnknownApplicationsRejected() {
        IllegalStateException cause = new IllegalStateException("business failed: internal detail");
        List<CommandService.Failure> failures = new ArrayList<>();
        CommandService service =
                new CommandService((p, a) -> Mono.just(state()), 1)
                        .onError(failures::add)
                        .register(
                                "Fail",
                                false,
                                (a, n) -> Mono.error(cause));
        CommandService.Response r =
                service.handle(
                                "POST",
                                "/api/commands/1/Fail",
                                headers("POST", "/api/commands/1/Fail"),
                                new byte[0])
                        .block();
        assertEquals(500, r.status);
        assertNotNull(r.headers.get("X-Signature"));
        assertEquals("Command failed", Json.parse(r.body.blockFirst()).path("error").asText());
        assertEquals(1, failures.size());
        CommandService.Failure failure = failures.get(0);
        assertSame(cause, failure.cause);
        assertEquals("region", failure.platformId);
        assertEquals("app", failure.applicationId);
        assertEquals("Fail", failure.commandId);
        assertEquals("0123456789abcdef0123456789abcdef", failure.requestId);
        r.close();
        assertEquals(0, service.inFlight());
        CommandService empty = new CommandService((p, a) -> Mono.empty(), 1);
        assertThrows(
                SecurityException.class,
                () ->
                        empty.handle(
                                        "GET",
                                        "/api/capabilities",
                                        headers("GET", "/api/capabilities"),
                                        new byte[0])
                                .block());
    }

    @Test
    void streamingFailureRetainsOriginalCauseAndReleasesBudget() {
        IllegalStateException cause = new IllegalStateException("stream failed");
        List<CommandService.Failure> failures = new ArrayList<>();
        CommandService service = new CommandService((p, a) -> Mono.just(state()), 1)
                .onError(failures::add)
                .register("Stream", true, (a, n) -> Flux.just(1).concatWith(Mono.error(cause)));
        try (CommandService.Response response = service.handle("POST", "/api/commands/1/Stream",
                headers("POST", "/api/commands/1/Stream"), new byte[0]).block()) {
            assertEquals(200, response.status);
            assertNotNull(response.headers.get("X-Signature"));
            assertTrue(failures.isEmpty());
            StepVerifier.create(response.body).expectNextCount(1)
                    .expectErrorMatches(error -> error == cause).verify();
            assertEquals(1, failures.size());
            assertSame(cause, failures.get(0).cause);
            assertEquals("Stream", failures.get(0).commandId);
            assertEquals(0, service.inFlight());
        }
    }

    @Test
    void serializationLimitFailureIsReportedAndReleasesBudget() {
        char[] text = new char[1024 * 1024];
        Arrays.fill(text, 'x');
        List<CommandService.Failure> failures = new ArrayList<>();
        CommandService service = new CommandService((p, a) -> Mono.just(state()), 1)
                .onError(failures::add)
                .register("Large", false, (a, n) -> Mono.just(new String(text)));
        try (CommandService.Response response = service.handle("POST", "/api/commands/1/Large",
                headers("POST", "/api/commands/1/Large"), new byte[0]).block()) {
            StepVerifier.create(response.body).expectError(IllegalArgumentException.class).verify();
            assertEquals(1, failures.size());
            assertEquals("Large", failures.get(0).commandId);
            assertEquals(0, service.inFlight());
        }
    }

    @Test
    void defaultLoggingAndFailingObserverPreserveCauseAndSignedError() {
        Logger logger = Logger.getLogger(CommandService.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        boolean useParents = logger.getUseParentHandlers();
        java.util.logging.Level level = logger.getLevel();
        logger.setUseParentHandlers(false);
        logger.setLevel(java.util.logging.Level.ALL);
        logger.addHandler(capture);
        try {
            IllegalStateException cause = new IllegalStateException("handler failed");
            IllegalStateException observerError = new IllegalStateException("observer failed");
            CommandService service = new CommandService((p, a) -> Mono.just(state()), 1)
                    .register("Fail", false, (a, n) -> Mono.error(cause));
            String target = "/api/commands/1/Fail";
            try (CommandService.Response response = service.handle("POST", target,
                    headers("POST", target), new byte[0]).block()) {
                assertEquals(500, response.status);
                assertEquals("Command failed", Json.parse(response.body.blockFirst()).path("error").asText());
            }
            assertEquals(1, records.size());
            assertSame(cause, records.get(0).getThrown());
            assertTrue(records.get(0).getMessage().contains("requestId=0123456789abcdef0123456789abcdef"));
            assertTrue(records.get(0).getMessage().contains("commandId=Fail"));
            records.clear();
            service.onError(failure -> { throw observerError; });
            try (CommandService.Response response = service.handle("POST", target,
                    headers("POST", target), new byte[0]).block()) {
                assertEquals(500, response.status);
                assertNotNull(response.headers.get("X-Signature"));
                assertEquals("Command failed", Json.parse(response.body.blockFirst()).path("error").asText());
            }
            assertEquals(2, records.size());
            assertSame(cause, records.get(0).getThrown());
            assertSame(observerError, records.get(1).getThrown());
            assertEquals(0, service.inFlight());
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(useParents);
            logger.setLevel(level);
        }
    }
}
