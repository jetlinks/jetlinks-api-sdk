package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.client.crypto.*;
import org.jetlinks.sdk.client.notification.*;
import org.junit.jupiter.api.*;

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
        ObjectNode event = Json.MAPPER.createObjectNode();
        event.put("id", "evt-" + type + "-" + time).put("type", type).put("occurredAt", time);
        ObjectNode data = event.putObject("data");
        data.putObject("application")
                .put("id", "app")
                .put("templateId", "template")
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

    @Test
    void recreatedBeforeOldDeletionUsesNewCredentialsAndRejectsOldEvents() {
        ApplicationState state =
                ApplicationLifecycle.apply(
                        null, notice("application.created", 100, 3, "https://p/old"), k -> pem);
        state =
                ApplicationLifecycle.apply(
                        state,
                        notice("application.created", 400, 1, "https://p/recreated"),
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
                        notice("application.created", 400, 1, "https://p/recreated"),
                        k -> pem);
        assertEquals(1, state.keyRevision);
        assertTrue(state.active());
        state =
                ApplicationLifecycle.apply(
                        state, notice("application.deleted", 300, 0, "https://p/api"), k -> pem);
        assertTrue(state.active());
    }
}
