package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.auth.OAuth2;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

class BusinessApplicationCredentialsTest {
    @Test
    void authenticatesWithoutBearerAndValidatesResponseBeforeDecrypt() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
        var mismatch = new AtomicBoolean();
        var server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (req, res) ->
                                        req.receive()
                                                .aggregate()
                                                .asByteArray()
                                                .flatMap(
                                                        bytes -> {
                                                            try {
                                                                assertEquals(
                                                                        "/gateway/business-application-template/template/app/credential/_encrypted",
                                                                        req.uri());
                                                                assertFalse(
                                                                        req.requestHeaders()
                                                                                .contains(
                                                                                        "Authorization"));
                                                                var body = Json.parse(bytes);
                                                                assertEquals(
                                                                        "",
                                                                        body.path("platformId")
                                                                                .textValue());
                                                                var verifier =
                                                                        Signature.getInstance(
                                                                                "RSASSA-PSS");
                                                                verifier.setParameter(
                                                                        new PSSParameterSpec(
                                                                                "SHA-256",
                                                                                "MGF1",
                                                                                MGF1ParameterSpec
                                                                                        .SHA256,
                                                                                32,
                                                                                1));
                                                                verifier.initVerify(
                                                                        pair.getPublic());
                                                                verifier.update(
                                                                        Signatures.lp(
                                                                                "business-application-credential/request",
                                                                                "POST",
                                                                                "1",
                                                                                "template",
                                                                                "app",
                                                                                "template",
                                                                                "credential.read",
                                                                                body.path("nonce")
                                                                                        .textValue(),
                                                                                body.path(
                                                                                                "timestamp")
                                                                                        .asText()));
                                                                assertTrue(
                                                                        verifier.verify(
                                                                                Base64
                                                                                        .getUrlDecoder()
                                                                                        .decode(
                                                                                                body.path(
                                                                                                                "sign")
                                                                                                        .textValue())));
                                                                var cipher =
                                                                        Cipher.getInstance(
                                                                                "RSA/ECB/OAEPPadding");
                                                                cipher.init(
                                                                        Cipher.ENCRYPT_MODE,
                                                                        pair.getPublic(),
                                                                        new OAEPParameterSpec(
                                                                                "SHA-256",
                                                                                "MGF1",
                                                                                MGF1ParameterSpec
                                                                                        .SHA256,
                                                                                PSource.PSpecified
                                                                                        .DEFAULT));
                                                                var response =
                                                                        new LinkedHashMap<
                                                                                String, Object>();
                                                                response.put("version", "1");
                                                                response.put(
                                                                        "platformId", "template");
                                                                response.put(
                                                                        "templateId", "template");
                                                                response.put(
                                                                        "applicationId", "app");
                                                                response.put(
                                                                        "usage", "credential.read");
                                                                response.put(
                                                                        "nonce",
                                                                        mismatch.get()
                                                                                ? "other"
                                                                                : body.path("nonce")
                                                                                        .textValue());
                                                                response.put("keyId", "rsa-1");
                                                                response.put("keyRevision", 2);
                                                                response.put(
                                                                        "accessKey", "access-key");
                                                                response.put(
                                                                        "encryptedAccessSecret",
                                                                        Base64.getEncoder()
                                                                                .encodeToString(
                                                                                        cipher
                                                                                                .doFinal(
                                                                                                        "application-secret"
                                                                                                                .getBytes(
                                                                                                                        java
                                                                                                                                .nio
                                                                                                                                .charset
                                                                                                                                .StandardCharsets
                                                                                                                                .UTF_8))));
                                                                return res.header(
                                                                                "Content-Type",
                                                                                "application/json")
                                                                        .sendString(
                                                                                Mono.just(
                                                                                        Json.MAPPER
                                                                                                .writeValueAsString(
                                                                                                        Map
                                                                                                                .of(
                                                                                                                        "status",
                                                                                                                        200,
                                                                                                                        "result",
                                                                                                                        response))))
                                                                        .then();
                                                            } catch (Exception e) {
                                                                return Mono.error(e);
                                                            }
                                                        }))
                        .bindNow();
        try (var reader =
                new BusinessApplicationCredentials(
                        "http://127.0.0.1:" + server.port() + "/gateway", "rsa-1", pem)) {
            var credential = reader.read("template", "app", "").block();
            assertNotNull(credential);
            assertEquals("application-secret", credential.accessSecret());
            assertEquals(2, credential.keyRevision());
            assertFalse(credential.toString().contains(credential.accessSecret()));
            assertTrue(
                    OAuth2.localTokens(
                                    credential.accessKey(),
                                    credential.accessSecret(),
                                    null,
                                    java.time.Duration.ofMinutes(2))
                            .token()
                            .block()
                            .startsWith("at_"));
            mismatch.set(true);
            assertEquals(
                    SdkException.Code.AUTHENTICATION,
                    assertThrows(
                                    SdkException.class,
                                    () -> reader.read("template", "app", "").block())
                            .code);
        } finally {
            server.disposeNow();
        }
    }
}
