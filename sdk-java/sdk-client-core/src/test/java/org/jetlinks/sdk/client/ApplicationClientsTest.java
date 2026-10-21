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
            state.updatedAt = 2;
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

    private void usable(ApplicationClients.Handle handle) {
        handle.client.assetScope(scope -> scope.organization("A")).close();
    }

    private void closed(ApplicationClients.Handle handle) {
        assertEquals(
                SdkException.Code.INVALID_STATE,
                assertThrows(SdkException.class, () -> usable(handle)).code);
    }

    private void rejected(ApplicationClients clients, ApplicationState state) {
        assertEquals(
                SdkException.Code.INVALID_STATE,
                assertThrows(
                                SdkException.class,
                                () -> clients.get("n", state, ApplicationClients.Mode.LOCAL))
                        .code);
    }

    @Test
    void staleSnapshotsCannotReplaceNewClientsAcrossModesOrScopes() {
        try (ApplicationClients clients = new ApplicationClients(8)) {
            ApplicationState state = state();
            state.updatedAt = 10;
            var old = clients.get("n", state, ApplicationClients.Mode.LOCAL);
            var scoped = clients.get("n", state, ApplicationClients.Mode.LOCAL, List.of(), null);
            var bearer = clients.get("n", state, ApplicationClients.Mode.BEARER, null, "user");
            ApplicationState rotated = state.copy();
            rotated.keyRevision = 2;
            rotated.accessSecret = "new";
            var next = clients.get("n", rotated, ApplicationClients.Mode.LOCAL);
            closed(old);
            closed(scoped);
            closed(bearer);
            assertEquals(
                    SdkException.Code.INVALID_STATE,
                    assertThrows(
                                    SdkException.class,
                                    () ->
                                            clients.get(
                                                    "n",
                                                    state,
                                                    ApplicationClients.Mode.OAUTH2,
                                                    List.of("unseen"),
                                                    null))
                            .code);
            usable(next);
            ApplicationState moved = rotated.copy();
            moved.updatedAt = 20;
            moved.apiBaseUrl = "http://localhost/new";
            var latest = clients.get("n", moved, ApplicationClients.Mode.LOCAL);
            closed(next);
            ApplicationState incomparable = rotated.copy();
            incomparable.keyRevision = 3;
            rejected(clients, incomparable);
            ApplicationState conflict = moved.copy();
            conflict.accessSecret = "conflict";
            rejected(clients, conflict);
            conflict = moved.copy();
            conflict.apiBaseUrl = "http://localhost/conflict";
            rejected(clients, conflict);
            ApplicationState disabled = moved.copy();
            disabled.updatedAt = 19;
            disabled.enabled = false;
            rejected(clients, disabled);
            assertSame(latest, clients.get("n", moved, ApplicationClients.Mode.LOCAL));
            usable(latest);
        }
    }

    @Test
    void inactiveWatermarkSurvivesInvalidateAndRecreationIsIsolated() {
        try (ApplicationClients clients = new ApplicationClients(4)) {
            ApplicationState state = state();
            state.updatedAt = 10;
            var east = clients.get("n", state, ApplicationClients.Mode.LOCAL);
            ApplicationState west = state.copy();
            west.platformId = "west";
            var other = clients.get("n", west, ApplicationClients.Mode.LOCAL);
            ApplicationState disabled = state.copy();
            disabled.updatedAt = 20;
            disabled.enabled = false;
            assertEquals(
                    SdkException.Code.INVALID_ARGUMENT,
                    assertThrows(SdkException.class, () -> clients.get("n", disabled)).code);
            closed(east);
            usable(other);
            clients.invalidate("n");
            rejected(clients, state);
            ApplicationState enabled = state.copy();
            enabled.updatedAt = 21;
            var live = clients.get("n", enabled, ApplicationClients.Mode.LOCAL);
            ApplicationState deleted = enabled.copy();
            deleted.deletedAt = 21;
            deleted.enabled = false;
            deleted.accessKey = null;
            deleted.accessSecret = null;
            assertEquals(
                    SdkException.Code.INVALID_ARGUMENT,
                    assertThrows(SdkException.class, () -> clients.get("n", deleted)).code);
            closed(live);
            enabled.updatedAt = 30;
            rejected(clients, enabled);
            ApplicationState recreated = state.copy();
            recreated.createdAt = 40;
            recreated.updatedAt = 40;
            var fresh = clients.get("n", recreated, ApplicationClients.Mode.LOCAL);
            deleted.updatedAt = 100;
            deleted.deletedAt = 100;
            rejected(clients, deleted);
            usable(fresh);
        }
    }

    @Test
    void versionHistoryRemainsBoundedAfterInvalidation() {
        try (ApplicationClients clients = new ApplicationClients(1)) {
            clients.get("a", state(), ApplicationClients.Mode.LOCAL);
            clients.invalidate("a");
            assertEquals(
                    SdkException.Code.LIMIT_EXCEEDED,
                    assertThrows(
                                    SdkException.class,
                                    () -> clients.get("b", state(), ApplicationClients.Mode.LOCAL))
                            .code);
            usable(clients.get("a", state(), ApplicationClients.Mode.LOCAL));
        }
    }
}
