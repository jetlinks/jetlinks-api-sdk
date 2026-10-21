package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.client.crypto.*;
import org.jetlinks.sdk.client.notification.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

class LifecycleTest {
    static KeyPair pair;
    static String pem, cipher;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        pair = gen.generateKeyPair();
        pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(
                Cipher.ENCRYPT_MODE,
                pair.getPublic(),
                new OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        cipher =
                Base64.getEncoder()
                        .encodeToString(
                                c.doFinal("application-secret".getBytes(StandardCharsets.UTF_8)));
    }

    public static VerifiedNotification notice(String type, long time, int revision, String base) {
        return notice(type, time, revision, base, 100, "enabled");
    }

    private static VerifiedNotification notice(
            String type, long time, int revision, String base, long createdAt, String state) {
        ObjectNode event = Json.MAPPER.createObjectNode();
        event.put("id", "evt-" + type + "-" + time).put("type", type).put("occurredAt", time);
        ObjectNode data = event.putObject("data");
        data.putObject("application")
                .put("id", "app")
                .put("templateId", "template")
                .put("createTime", createdAt)
                .put("state", state)
                .put("apiBaseUrl", base);
        if (revision > 0)
            data.putObject("payload")
                    .putObject("credential")
                    .put("accessKey", "access-" + revision)
                    .put("encryptedAccessSecret", cipher)
                    .put("keyId", "receiver")
                    .put("keyRevision", revision);
        byte[] body = Json.bytes(event);
        String now = Long.toString(System.currentTimeMillis());
        Map<String, List<String>> h = new LinkedHashMap<>();
        String[] names = {
            "X-Signature-Version",
            "X-Platform-Id",
            "X-Application-Id",
            "X-Key-Id",
            "X-Timestamp",
            "X-Event-Id",
            "X-Signature"
        };
        String[] values = {
            "1",
            "region",
            "app",
            "notify",
            now,
            event.path("id").asText(),
            Signatures.sign(
                    "notification-secret",
                    "1",
                    "app",
                    "region",
                    "notify",
                    now,
                    event.path("id").asText(),
                    Signatures.sha256(body))
        };
        for (int i = 0; i < names.length; i++)
            h.put(names[i], Collections.singletonList(values[i]));
        return new NotificationVerifier(
                        (p, a, k) ->
                                Mono.just(
                                        TrustedKey.business(
                                                "region",
                                                "template",
                                                "app",
                                                "notification-secret")))
                .verify(h, body)
                .block();
    }

    @Test
    void rsaAndSyncedRecovery() {
        assertEquals("application-secret", Signatures.decrypt(cipher, pem));
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null,
                        notice("application.credential.synced", 100, 2, "https://p/api"),
                        k -> pem);
        assertEquals(2, state.keyRevision);
        assertTrue(state.active());
        ApplicationState unchanged =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.credential.synced", 110, 2, "https://p/api"),
                        k -> pem);
        assertEquals(state.accessKey, unchanged.accessKey);
        assertEquals(2, unchanged.keyRevision);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "disabled"})
    void creationPreservesPlatformState(String platformState) {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null,
                        notice("application.created", 110, 1, "https://p/api", 100, platformState),
                        k -> pem);
        assertEquals("enabled".equals(platformState), state.enabled);
        assertEquals("enabled".equals(platformState), state.active());
        assertEquals(100, state.createdAt);
    }

    @Test
    void syncBeforeCreationRejectsOldLifecycleStateAndCredentials() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null,
                        notice(
                                "application.credential.synced",
                                500,
                                1,
                                "https://p/new",
                                400,
                                "enabled"),
                        k -> pem);
        for (String type :
                List.of(
                        "application.created",
                        "application.credential.rotated",
                        "application.credential.synced",
                        "application.disabled",
                        "application.deleted")) {
            // 即使旧事实迟至新同步之后才形成通知，也只能影响它原属的生命周期。
            state =
                    ApplicationLifecycle.apply(
                            state,
                            notice(type, 600, 3, "https://p/old", 100, "disabled"),
                            k -> pem);
            assertTrue(state.active(), type);
            assertEquals(400, state.createdAt, type);
            assertEquals(1, state.keyRevision, type);
            assertEquals("access-1", state.accessKey, type);
            assertEquals("https://p/new", state.apiBaseUrl, type);
        }
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.created", 410, 1, "https://p/initial", 400, "enabled"),
                        k -> pem);
        assertEquals(500, state.updatedAt);
        assertEquals("https://p/new", state.apiBaseUrl);
    }

    @Test
    void syncCanRestoreRecreatedApplicationAfterPreviousDeletion() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.created", 100, 3, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.deleted", 300, 0, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice(
                                "application.credential.synced",
                                500,
                                1,
                                "https://p/new",
                                400,
                                "disabled"),
                        k -> pem);
        assertEquals(0, state.deletedAt);
        assertEquals(400, state.createdAt);
        assertEquals(1, state.keyRevision);
        assertEquals("access-1", state.accessKey);
        assertFalse(state.enabled);
        assertFalse(state.active());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://192.168.35.103:9111/api",
                "http://platform.example/gateway/api",
                "https://platform.example/gateway/api",
                "http://127.0.0.1:9111/api",
                "http://[::1]:9111/api"
            })
    void acceptsPlatformHttpAndHttpsAddresses(String base) {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.updated", 100, 0, base), key -> null);
        assertEquals(base, state.apiBaseUrl);
        assertEquals(
                base + "/authorize/context", ApiRequest.get("/authorize/context").target(base));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/api",
                "ftp://platform.example/api",
                "https://user:secret@platform.example/api",
                "http://platform.example/api?x=1",
                "http://platform.example/api#fragment",
                "http://platform.example:65536/api"
            })
    void rejectsInvalidPlatformAddresses(String base) {
        assertThrows(
                SdkException.class,
                () ->
                        ApplicationLifecycle.apply(
                                null, notice("application.updated", 100, 0, base), key -> null));
    }

    @Test
    void recreatedBeforeOldDeletionUsesNewCredentialsAndRejectsOldEvents() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.created", 100, 3, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice(
                                "application.created",
                                400,
                                1,
                                "https://p/recreated",
                                400,
                                "enabled"),
                        k -> pem);
        assertEquals(400, state.createdAt);
        assertEquals(1, state.keyRevision);
        assertEquals("access-1", state.accessKey);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.deleted", 300, 0, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.credential.rotated", 250, 4, "https://p/old"),
                        k -> pem);
        assertTrue(state.active());
        assertEquals(1, state.keyRevision);
        assertEquals("https://p/recreated", state.apiBaseUrl);
    }

    @Test
    void repeatedCreatedDoesNotResetNewerCredentialsOrState() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.created", 100, 1, "https://p/initial"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.credential.rotated", 200, 2, "https://p/new"),
                        k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.disabled", 300, 0, "https://p/new"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.created", 100, 1, "https://p/initial"),
                        k -> pem);
        assertEquals(100, state.createdAt);
        assertEquals(2, state.keyRevision);
        assertEquals("https://p/new", state.apiBaseUrl);
        assertFalse(state.active());
    }

    @Test
    void lateInitialCreationPreservesStateRecoveredByCredentialSync() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null,
                        notice("application.credential.synced", 200, 3, "https://p/current"),
                        k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.created", 100, 1, "https://p/initial"),
                        k -> pem);
        assertEquals(100, state.createdAt);
        assertEquals(3, state.keyRevision);
        assertEquals("https://p/current", state.apiBaseUrl);
    }

    @Test
    void outOfOrderDeleteRecreateAndRevisionReset() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.created", 100, 3, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.updated", 200, 0, "https://p/new"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.updated", 150, 0, "https://p/stale"), k -> pem);
        assertEquals("https://p/new", state.apiBaseUrl);
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.deleted", 300, 0, "https://p/api"), k -> pem);
        assertFalse(state.active());
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.credential.synced", 350, 3, "https://p/api"),
                        k -> pem);
        assertFalse(state.active());
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice(
                                "application.created",
                                400,
                                1,
                                "https://p/recreated",
                                400,
                                "enabled"),
                        k -> pem);
        assertEquals(1, state.keyRevision);
        assertTrue(state.active());
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.deleted", 300, 0, "https://p/api"), k -> pem);
        assertTrue(state.active());
    }
}
