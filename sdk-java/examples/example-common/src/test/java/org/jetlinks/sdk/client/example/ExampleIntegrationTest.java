package org.jetlinks.sdk.client.example;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.crypto.*;
import org.jetlinks.sdk.client.notification.ApplicationState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.*;
import javax.crypto.spec.*;

class ExampleIntegrationTest {
    @TempDir Path temp;
    private final long applicationCreatedAt = System.currentTimeMillis();

    JsonNode read(ExampleResponse r) {
        return Json.parse(
                org.jetlinks.sdk.client.internal.BoundedBytes.collect(r.body, 2 * 1024 * 1024)
                        .block());
    }

    ExampleConfig configuration(String base) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
        Files.write(temp.resolve("private.pem"), pem.getBytes(StandardCharsets.US_ASCII));
        Files.write(temp.resolve("public.der"), pair.getPublic().getEncoded());
        ExampleConfig c = new ExampleConfig();
        c.database = temp.resolve("example").toString();
        for (String platform : Arrays.asList("east", "west")) {
            ExampleConfig.Template t = new ExampleConfig.Template();
            t.platformId = platform;
            t.templateId = "template";
            t.notificationKeyId = "notify";
            t.notificationSecret = "notification-secret";
            t.receiverKeyId = "receiver";
            t.privateKeyFile = temp.resolve("private.pem").toString();
            c.templates.add(t);
        }
        ExampleConfig.ApiApplication api = new ExampleConfig.ApiApplication();
        api.contextId = "api";
        api.applicationId = "api-app";
        api.apiBaseUrl = base;
        api.accessKey = "api-key";
        api.accessSecret = "api-secret";
        c.apiApplications.add(api);
        return c;
    }

    ObjectNode event(String platform, String type, String id, String base) throws Exception {
        PublicKey key =
                KeyFactory.getInstance("RSA")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Files.readAllBytes(temp.resolve("public.der"))));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                new OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        String encrypted =
                Base64.getEncoder()
                        .encodeToString(
                                cipher.doFinal("machine-secret".getBytes(StandardCharsets.UTF_8)));
        ObjectNode event = Json.MAPPER.createObjectNode();
        event.put("id", id).put("type", type).put("occurredAt", System.currentTimeMillis());
        ObjectNode data = event.putObject("data");
        data.putObject("application")
                .put("id", "same-app")
                .put("templateId", "template")
                .put("createTime", applicationCreatedAt)
                .put("apiBaseUrl", base);
        data.putObject("payload")
                .putObject("credential")
                .put("accessKey", "machine-key")
                .put("encryptedAccessSecret", encrypted)
                .put("keyId", "receiver")
                .put("keyRevision", 1);
        return event;
    }

    Map<String, List<String>> signed(String platform, ObjectNode event) {
        String time = Long.toString(System.currentTimeMillis());
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
            platform,
            "same-app",
            "notify",
            time,
            event.path("id").asText(),
            Signatures.sign(
                    "notification-secret",
                    "1",
                    "same-app",
                    platform,
                    "notify",
                    time,
                    event.path("id").asText(),
                    Signatures.sha256(Json.bytes(event)))
        };
        for (int i = 0; i < names.length; i++)
            h.put(names[i], Collections.singletonList(values[i]));
        return h;
    }

    @Test
    void realHttpCallsDurableNotificationsAndRestart() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        DisposableServer platform =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) -> {
                                    if (req.uri().equals("/gateway/oauth2/token")) {
                                        tokenRequests.incrementAndGet();
                                        return res.sendString(
                                                Mono.just(
                                                        "{\"access_token\":\"fixture-token\",\"expires_in\":900}"));
                                    }
                                    if (req.uri().endsWith("/_query"))
                                        return res.sendString(
                                                Mono.just(
                                                        "{\"result\":{\"total\":0,\"data\":[]}}"));
                                    return res.sendString(
                                            Mono.just(
                                                    "{\"result\":{\"message\":\"real HTTP"
                                                            + " result\"}}"));
                                })
                        .bindNow();
        String base = "http://127.0.0.1:" + platform.port() + "/gateway";
        ExampleConfig config = configuration(base);
        try {
            try (ExampleService service = new ExampleService(config)) {
                ExampleResponse result =
                        service.handle(
                                        "POST",
                                        "/example-api/applications/api/requests",
                                        Collections.emptyMap(),
                                        Json.bytes(
                                                ExampleService.map(
                                                        "path",
                                                        "/authorize/context",
                                                        "client",
                                                        "sync",
                                                        "authMode",
                                                        "oauth2")))
                                .block();
                assertEquals(
                        "real HTTP result", read(result).path("result").path("message").asText());
                assertEquals(1, tokenRequests.get());
                for (String region : Arrays.asList("east", "west")) {
                    ObjectNode event =
                            event(region, "application.created", "created-" + region, base);
                    assertEquals(
                            204,
                            service.handle(
                                            "POST",
                                            "/jetlinks/events",
                                            signed(region, event),
                                            Json.bytes(event))
                                    .block()
                                    .status);
                    assertEquals(
                            204,
                            service.handle(
                                            "POST",
                                            "/jetlinks/events",
                                            signed(region, event),
                                            Json.bytes(event))
                                    .block()
                                    .status);
                }
                assertEquals(2, service.store.applications().block().size());
                JsonNode receipts =
                        read(
                                service.handle(
                                                "GET",
                                                "/example-api/notifications?platformId=east",
                                                Collections.emptyMap(),
                                                new byte[0])
                                        .block());
                assertEquals(2, receipts.path("total").asInt());
                assertTrue(receipts.path("data").get(0).has("signatureHeaders"));
                assertTrue(receipts.toString().contains("[redacted]"));
            }
            try (ExampleService restarted = new ExampleService(config)) {
                ApplicationState state = restarted.store.find("east", "same-app").block();
                assertEquals("machine-secret", state.accessSecret);
                assertEquals(base, state.apiBaseUrl);
                ObjectNode sync = event("east", "application.credential.synced", "sync", base);
                assertEquals(
                        204,
                        restarted
                                .handle(
                                        "POST",
                                        "/jetlinks/events",
                                        signed("east", sync),
                                        Json.bytes(sync))
                                .block()
                                .status);
                assertEquals(
                        "machine-key", restarted.store.find("east", "same-app").block().accessKey);
                assertEquals(1, restarted.store.find("east", "same-app").block().keyRevision);
                String id = "business-" + ExampleStore.identity("east", "same-app");
                JsonNode facts =
                        read(
                                restarted
                                        .handle(
                                                "POST",
                                                "/example-api/applications/"
                                                        + id
                                                        + "/authorization",
                                                Collections.emptyMap(),
                                                Json.bytes(Collections.emptyMap()))
                                        .block());
                assertTrue(facts.has("members"));
                assertTrue(facts.has("roles"));
                String factsPath = "/example-api/applications/" + id + "/authorization";
                assertTrue(
                        read(restarted
                                        .handle(
                                                "GET",
                                                factsPath,
                                                Collections.emptyMap(),
                                                new byte[0])
                                        .block())
                                .has("members"));
                long changedAt = sync.path("occurredAt").asLong() + 1;
                ObjectNode rotated =
                        event("east", "application.credential.rotated", "rotated", base);
                rotated.put("occurredAt", changedAt);
                ((ObjectNode) rotated.path("data").path("payload").path("credential"))
                        .put("keyRevision", 2);
                assertEquals(
                        204,
                        restarted
                                .handle(
                                        "POST",
                                        "/jetlinks/events",
                                        signed("east", rotated),
                                        Json.bytes(rotated))
                                .block()
                                .status);
                assertEquals(
                        "not synchronized",
                        read(restarted
                                        .handle(
                                                "GET",
                                                factsPath,
                                                Collections.emptyMap(),
                                                new byte[0])
                                        .block())
                                .path("state")
                                .asText());
                assertTrue(
                        read(restarted
                                        .handle(
                                                "POST",
                                                factsPath,
                                                Collections.emptyMap(),
                                                Json.bytes(Collections.emptyMap()))
                                        .block())
                                .has("members"));
                ObjectNode deleted = event("east", "application.deleted", "deleted", base);
                deleted.put("occurredAt", changedAt + 1);
                assertEquals(
                        204,
                        restarted
                                .handle(
                                        "POST",
                                        "/jetlinks/events",
                                        signed("east", deleted),
                                        Json.bytes(deleted))
                                .block()
                                .status);
                assertFalse(restarted.store.find("east", "same-app").block().active());
                ObjectNode recreated = event("east", "application.created", "recreated", base);
                recreated.put("occurredAt", changedAt + 2);
                ((ObjectNode) recreated.path("data").path("application"))
                        .put("createTime", changedAt + 2);
                assertEquals(
                        204,
                        restarted
                                .handle(
                                        "POST",
                                        "/jetlinks/events",
                                        signed("east", recreated),
                                        Json.bytes(recreated))
                                .block()
                                .status);
                assertEquals(1, restarted.store.find("east", "same-app").block().keyRevision);
                ObjectNode lateRotation =
                        event("east", "application.credential.rotated", "late-old-rotation", base);
                lateRotation.put("occurredAt", changedAt + 3);
                ((ObjectNode) lateRotation.path("data").path("payload").path("credential"))
                        .put("keyRevision", 3);
                assertEquals(
                        204,
                        restarted
                                .handle(
                                        "POST",
                                        "/jetlinks/events",
                                        signed("east", lateRotation),
                                        Json.bytes(lateRotation))
                                .block()
                                .status);
                assertEquals(1, restarted.store.find("east", "same-app").block().keyRevision);
                assertEquals(
                        "not synchronized",
                        read(restarted
                                        .handle(
                                                "GET",
                                                factsPath,
                                                Collections.emptyMap(),
                                                new byte[0])
                                        .block())
                                .path("state")
                                .asText());
                assertTrue(
                        read(restarted
                                        .handle(
                                                "POST",
                                                factsPath,
                                                Collections.emptyMap(),
                                                Json.bytes(Collections.emptyMap()))
                                        .block())
                                .has("members"));
            }
        } finally {
            platform.disposeNow();
        }
    }

    @Test
    void rejectedAndInvalidCredentialNeverRegisterApplication() throws Exception {
        ExampleConfig config = configuration("http://127.0.0.1:1");
        try (ExampleService service = new ExampleService(config)) {
            assertEquals(
                    401,
                    service.handle(
                                    "POST",
                                    "/jetlinks/events",
                                    Collections.emptyMap(),
                                    Json.bytes(Collections.singletonMap("id", "bad")))
                            .block()
                            .status);
            ObjectNode invalid =
                    event("east", "application.created", "retryable", "http://127.0.0.1:1");
            ((ObjectNode) invalid.path("data").path("payload").path("credential"))
                    .put("encryptedAccessSecret", "broken");
            assertEquals(
                    401,
                    service.handle(
                                    "POST",
                                    "/jetlinks/events",
                                    signed("east", invalid),
                                    Json.bytes(invalid))
                            .block()
                            .status);
            assertTrue(service.store.applications().block().isEmpty());
            ObjectNode valid =
                    event("east", "application.created", "retryable", "http://127.0.0.1:1");
            assertEquals(
                    204,
                    service.handle(
                                    "POST",
                                    "/jetlinks/events",
                                    signed("east", valid),
                                    Json.bytes(valid))
                            .block()
                            .status);
            assertEquals(1, service.store.applications().block().size());
        }
    }
}
