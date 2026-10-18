package org.jetlinks.sdk.client.example;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.Json;
import org.jetlinks.sdk.client.SdkException;
import org.jetlinks.sdk.client.command.*;
import org.jetlinks.sdk.client.notification.ApplicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

class ExampleQuotaTest {
    @TempDir Path temp;
    final AtomicLong clock = new AtomicLong(1000);

    ExampleStore store() {
        return new ExampleStore(temp.resolve("quotas").toString(), key -> null, clock::get);
    }

    ApplicationState application(String platform, long createdAt) {
        ApplicationState application = new ApplicationState();
        application.platformId = platform;
        application.applicationId = "application";
        application.createdAt = createdAt;
        return application;
    }

    SaveTenantLimits limits(long revision, String amount) {
        SaveTenantLimits input = new SaveTenantLimits();
        input.revision = revision;
        SaveTenantLimits.Limit limit = new SaveTenantLimits.Limit();
        limit.metricId = ExampleService.REQUEST_METRIC;
        limit.limit = new BigDecimal(amount);
        limit.windowDurationMillis = 10000L;
        input.limits = List.of(limit);
        input.capabilities =
                List.of(
                        new ResourceCapability(
                                ExampleService.RESOURCE_ID, Set.of(ExampleService.USE_CAPABILITY)));
        return input;
    }

    TenantUsage usage(ExampleStore store, ApplicationState app) {
        return store.usage(app, new ReadTenantUsage()).single().block();
    }

    @Test
    void updatesAreAbsoluteKeepUsageAndRejectStaleVersionsAfterRestart() {
        var store = store();
        var app = application("east", 1);
        assertTrue(store.usage(app, new ReadTenantUsage()).collectList().block().isEmpty());
        assertEquals(1, store.saveLimits(app, limits(1, "1000")).block().revision);
        assertEquals(BigDecimal.ZERO, usage(store, app).usage);
        store.useResource(app, new BigDecimal("10.12345678901234567890123456789")).block();
        var before = usage(store, app);
        assertEquals(2, store.saveLimits(app, limits(2, "5")).block().revision);
        assertEquals(before.usage, usage(store, app).usage);
        assertThrows(SdkException.class, () -> store.useResource(app, BigDecimal.ONE).block());
        assertEquals(2, store.saveLimits(app, limits(1, "1000")).block().revision);
        assertEquals(2, store.saveLimits(app, limits(2, "5")).block().revision);
        var restarted = store();
        assertEquals(before.usage, usage(restarted, app).usage);
        assertEquals(2, restarted.saveLimits(app, limits(1, "1000")).block().revision);
        assertThrows(SdkException.class, () -> restarted.useResource(app, BigDecimal.ONE).block());
    }

    @Test
    void zeroUnlimitedRevocationAndRegrantDoNotEraseUsage() {
        var store = store();
        var app = application("east", 1);
        store.saveLimits(app, limits(0, "0")).block();
        assertThrows(SdkException.class, () -> store.useResource(app, BigDecimal.ONE).block());
        store.saveLimits(app, limits(1, "-2")).block();
        store.useResource(app, new BigDecimal("1234")).block();
        SaveTenantLimits revoke = new SaveTenantLimits();
        revoke.revision = 2L;
        revoke.limits = List.of();
        revoke.capabilities = List.of();
        store.saveLimits(app, revoke).block();
        assertTrue(store.usage(app, new ReadTenantUsage()).collectList().block().isEmpty());
        assertThrows(SdkException.class, () -> store.useResource(app, BigDecimal.ONE).block());
        store.saveLimits(app, limits(3, "2000")).block();
        assertEquals(new BigDecimal("1234"), usage(store, app).usage);
    }

    @Test
    void readsDoNotConsumeAndFixedWindowChangesAtPlatformBoundary() {
        var store = store();
        var app = application("east", 1);
        store.saveLimits(app, limits(1, "10")).block();
        store.useResource(app, BigDecimal.ONE).block();
        assertEquals(BigDecimal.ONE, usage(store, app).usage);
        assertEquals(BigDecimal.ONE, usage(store, app).usage);
        assertEquals(10000L, usage(store, app).nextResetTime);
        clock.set(10000);
        assertEquals(BigDecimal.ZERO, usage(store, app).usage);
        assertEquals(20000L, usage(store, app).nextResetTime);
        var input = new ReadTenantUsage();
        input.metricIds = List.of("other:requests");
        assertTrue(store.usage(app, input).collectList().block().isEmpty());
    }

    @Test
    void isolatesPlatformsAndLifecyclesAndDoesNotPartiallyAcceptInvalidBatch() {
        var store = store();
        var east = application("east", 1);
        store.saveLimits(east, limits(1, "10")).block();
        assertTrue(
                store.usage(application("west", 1), new ReadTenantUsage())
                        .collectList()
                        .block()
                        .isEmpty());
        assertTrue(
                store.usage(application("east", 2), new ReadTenantUsage())
                        .collectList()
                        .block()
                        .isEmpty());
        var anotherApp = application("east", 1);
        anotherApp.applicationId = "another-application";
        assertTrue(store.usage(anotherApp, new ReadTenantUsage()).collectList().block().isEmpty());
        SaveTenantLimits invalid = limits(2, "50");
        SaveTenantLimits.Limit unknown = new SaveTenantLimits.Limit();
        unknown.metricId = "other:requests";
        invalid.limits = List.of(invalid.limits.get(0), unknown);
        assertThrows(SdkException.class, () -> store.saveLimits(east, invalid).block());
        assertEquals(1, store.saveLimits(east, limits(1, "10")).block().revision);
        assertThrows(
                SdkException.class, () -> store.useResource(east, new BigDecimal("11")).block());
    }

    @Test
    void typedCommandParametersRetainDecimalPrecisionThroughTreeDecode() {
        byte[] json =
                "{\"revision\":1,\"limits\":[{\"metricId\":\"example:requests\",\"limit\":1.12345678901234567890123456789,\"windowDurationMillis\":10000}],\"capabilities\":[{\"resource\":\"example\",\"capabilities\":[\"use\"]}]}"
                        .getBytes(StandardCharsets.UTF_8);
        var decoded = Json.convert(Json.parse(json), SaveTenantLimits.class);
        assertEquals(
                new BigDecimal("1.12345678901234567890123456789"), decoded.limits.get(0).limit);
        assertEquals(Set.of("use"), decoded.capabilities.get(0).capabilities);
    }

    @Test
    void capabilitiesAreRequiredSavedAtomicallyAndRevokedIndependentlyOfUsage() {
        var store = store();
        var app = application("east", 1);
        var initial = limits(1, "10");
        initial.capabilities = null;
        assertThrows(SdkException.class, () -> store.saveLimits(app, initial).block());
        store.saveLimits(app, limits(1, "10")).block();
        store.useResource(app, BigDecimal.ONE).block();

        var invalid = limits(2, "20");
        invalid.capabilities =
                List.of(new ResourceCapability(ExampleService.RESOURCE_ID, Set.of("unknown")));
        assertThrows(SdkException.class, () -> store.saveLimits(app, invalid).block());
        assertEquals(1, store.saveLimits(app, limits(1, "10")).block().revision);

        var revoked = limits(2, "10");
        revoked.capabilities = List.of();
        store.saveLimits(app, revoked).block();
        assertEquals(BigDecimal.ONE, usage(store, app).usage);
        assertThrows(SdkException.class, () -> store.useResource(app, BigDecimal.ONE).block());
        var restarted = store();
        assertEquals(2, restarted.saveLimits(app, limits(1, "10")).block().revision);
        assertThrows(SdkException.class, () -> restarted.useResource(app, BigDecimal.ONE).block());
        store.saveLimits(app, limits(3, "10")).block();
        assertEquals(new BigDecimal("2"), store.useResource(app, BigDecimal.ONE).block().usage);
    }

    @Test
    void concurrentUsesDoNotExceedTheSavedQuota() {
        var store = store();
        var app = application("east", 1);
        store.saveLimits(app, limits(1, "5")).block();
        var accepted =
                Flux.range(0, 20)
                        .flatMap(
                                i ->
                                        store.useResource(app, BigDecimal.ONE)
                                                .thenReturn(true)
                                                .onErrorResume(
                                                        SdkException.class,
                                                        error -> {
                                                            assertEquals(
                                                                    SdkException.Code
                                                                            .LIMIT_EXCEEDED,
                                                                    error.code);
                                                            return Mono.just(false);
                                                        }),
                                8)
                        .filter(Boolean::booleanValue)
                        .count()
                        .block();
        assertEquals(5L, accepted);
        assertEquals(new BigDecimal("5"), usage(store, app).usage);
    }
}
