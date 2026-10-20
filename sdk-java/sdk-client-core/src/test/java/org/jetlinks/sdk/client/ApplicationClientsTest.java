package org.jetlinks.sdk.client;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.client.auth.LocalToken;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.notification.ApplicationState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

class ApplicationClientsTest {
    private ApplicationState state() {
        ApplicationState state = new ApplicationState();
        state.platformId = "east";
        state.applicationId = "app";
        state.apiBaseUrl = "http://localhost/prefix";
        state.accessKey = "key";
        state.accessSecret = "secret";
        state.createdAt = 1;
        state.keyRevision = 1;
        return state;
    }

    @Test
    void reuseRotateIsolateAndInvalidate() {
        ApplicationState state = state();
        try (ApplicationClients clients = new ApplicationClients(2)) {
            ApplicationClients.Handle old =
                    clients.get("channel", state, ApplicationClients.Mode.LOCAL);
            assertSame(old, clients.get("channel", state.copy(), ApplicationClients.Mode.LOCAL));
            assertNotSame(
                    old,
                    clients.get("channel", state, ApplicationClients.Mode.LOCAL, List.of(), null));
            assertThrows(
                    SdkException.class,
                    () -> clients.get("other", state, ApplicationClients.Mode.LOCAL));
            state.keyRevision = 2;
            assertNotSame(old, clients.get("channel", state, ApplicationClients.Mode.LOCAL));
            SdkException closed =
                    assertThrows(
                            SdkException.class,
                            () -> old.sync.request(ApiRequest.get("/authorize/context")));
            assertEquals(SdkException.Code.INVALID_STATE, closed.code);
            state.enabled = false;
            assertThrows(
                    SdkException.class,
                    () -> clients.get("channel", state, ApplicationClients.Mode.LOCAL));
            state.enabled = true;
            assertNotNull(clients.get("other", state, ApplicationClients.Mode.LOCAL));
        }
    }

    @Test
    void independentCrossLanguageVectors() throws Exception {
        var vectors =
                Json.parse(Files.readAllBytes(Paths.get("../../examples/contract/vectors.json")));
        var signature = vectors.path("signature");
        String[] fields = Json.convert(signature.path("fields"), String[].class);
        assertEquals(
                signature.path("expected").asText(),
                Signatures.sign(signature.path("secret").asText(), fields));
        for (var token : vectors.path("tokens")) {
            List<String> scopes =
                    token.path("scopes").isNull()
                            ? null
                            : Json.convert(token.path("scopes"), new TypeRef<List<String>>() {});
            assertEquals(
                    token.path("expected").asText(),
                    LocalToken.create(
                            token.path("key").asText(),
                            token.path("secret").asText(),
                            scopes,
                            token.path("issuedAt").asLong()));
        }
    }
}
