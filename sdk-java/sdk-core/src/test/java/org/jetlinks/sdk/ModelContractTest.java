package org.jetlinks.sdk;

import org.jetlinks.sdk.auth.*;
import org.jetlinks.sdk.command.*;
import org.jetlinks.sdk.crypto.*;
import org.jetlinks.sdk.event.*;
import org.jetlinks.sdk.model.*;
import org.jetlinks.sdk.notification.*;
import org.jetlinks.sdk.subscription.*;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModelContractTest {
    public static class Payload<T> {
        public T value;
    }

    public static class Parameters {
        public String applicationId, templateId;
        public int count;
    }

    private VerifiedNotification verify(String body, boolean business, String eventId) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis());
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Signature-Version", Collections.singletonList("1"));
        headers.put("X-Application-Id", Collections.singletonList("app"));
        headers.put("X-Key-Id", Collections.singletonList("key"));
        headers.put("X-Timestamp", Collections.singletonList(timestamp));
        headers.put("X-Event-Id", Collections.singletonList(eventId));
        if (business) headers.put("X-Platform-Id", Collections.singletonList("region"));
        headers.put("X-Signature", Collections.singletonList(Signatures.sign(
                "secret", "1", "app", business ? "region" : "", "key",
                timestamp, eventId, Signatures.sha256(raw))));
        return new NotificationVerifier((p, a, k) -> Mono.just(business
                ? TrustedKey.business("region", "template", "app", "secret")
                : TrustedKey.api("app", "secret"))).verify(headers, raw).block();
    }

    @Test
    void genericNotificationRetainsCustomDataAndOriginalBytes() {
        String raw = "{ \"id\":\"evt\", \"type\":\"application.custom\", \"future\":42,"
                + "\"subject\":\"device-1\",\"data\":{\"application\":{\"id\":\"app\","
                + "\"templateId\":\"template\"},\"sourceEventId\":\"source\","
                + "\"payload\":{\"value\":[{\"id\":\"member\",\"region\":\"east\"}]}}}";
        VerifiedNotification notice = verify(raw, true, "evt");
        BusinessApplicationEventData<Payload<List<Member>>> data = notice.data(
                new TypeRef<BusinessApplicationEventData<Payload<List<Member>>>>() {});
        assertEquals("app", notice.applicationId);
        assertEquals("device-1", notice.event.subject);
        assertEquals("source", data.sourceEventId);
        assertEquals("member", data.payload.value.get(0).id);
        assertEquals("east", data.payload.value.get(0).extensions().get("region"));
        assertEquals(42, notice.event.extensions().get("future"));
        assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8), notice.rawBody);
        // A future application.* event is delivered without being parsed as a lifecycle payload.
        ApplicationState state = ApplicationLifecycle.apply(null, notice, id -> null);
        assertEquals("app", state.applicationId);
        assertNull(state.accessSecret);
    }

    @Test
    void apiNotificationUsesHeaderIdentityAndStrictEventId() {
        VerifiedNotification notice = verify(
                "{\"id\":\"evt\",\"type\":\"custom\",\"data\":{\"id\":\"payload-id\"}}",
                false, "evt");
        Member member = notice.data(Member.class);
        assertEquals("payload-id", member.id);
        assertEquals("app", notice.applicationId);
        assertThrows(SecurityException.class,
                () -> verify("{\"id\":7,\"data\":{}}", false, "7"));
        assertThrows(SecurityException.class, () -> verify(
                "{\"id\":\"evt\",\"data\":{\"application\":{\"id\":\"other\","
                        + "\"templateId\":\"template\"}}}", true, "evt"));
    }

    @Test
    void lifecycleAndCatalogConstantsMatchWireFields() {
        VerifiedNotification notice = verify(
                "{\"id\":\"evt\",\"type\":\"application.credential.synced\",\"occurredAt\":123,"
                        + "\"data\":{\"application\":{\"id\":\"app\",\"templateId\":\"template\","
                        + "\"apiBaseUrl\":\"https://platform/api\",\"state\":\"enabled\"},"
                        + "\"payload\":{\"credential\":{\"accessKey\":\"access\","
                        + "\"encryptedAccessSecret\":\"cipher\",\"keyId\":\"receiver\","
                        + "\"keyRevision\":3},\"userIds\":[\"user\"],\"sections\":[\"members\"]}}}",
                true, "evt");
        BusinessApplicationEventData<LifecyclePayload> data = notice.data(
                new TypeRef<BusinessApplicationEventData<LifecyclePayload>>() {});
        assertEquals(EventTypes.APPLICATION_CREDENTIAL_SYNCED, notice.event.type);
        assertEquals("https://platform/api", data.application.apiBaseUrl);
        assertEquals(ApplicationInfo.ENABLED, data.application.state);
        assertEquals(3L, data.payload.credential.keyRevision.longValue());
        assertEquals("receiver", data.payload.credential.keyId);
        assertEquals("user", data.payload.userIds.get(0));
        assertEquals(LifecyclePayload.MEMBERS, data.payload.sections.get(0));
        assertEquals("X-Platform-Id", ProtocolHeaders.PLATFORM_ID);
        assertEquals("X-Application-Id", ProtocolHeaders.APPLICATION_ID);
        assertEquals("X-Event-Id", ProtocolHeaders.EVENT_ID);
        assertEquals("X-Request-Id", ProtocolHeaders.REQUEST_ID);
        assertEquals("http-hook", DeliveryChannel.HTTP_HOOK);
        assertFalse(EventTypes.isApplicationEvent("application.custom"));
    }

    private DisposableServer server() {
        // Responses are literal platform projections, independent of the DTO serializer.
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((req, res) -> req.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMapMany(body -> {
                            String path = req.uri().split("\\?", 2)[0];
                            if (path.equals("/stream") || path.equals("/nested-stream")) {
                                String row = path.equals("/stream")
                                        ? "{\"id\":\"user\"}" : "[{\"id\":\"user\"}]";
                                return res.header("Content-Type", "application/x-ndjson")
                                        .sendString(Flux.just(row + "\n", row + "\n"));
                            }
                            String result;
                            if (path.equals("/empty-token/oauth2/token"))
                                return res.sendString(Mono.just("null"));
                            if (path.equals("/oauth2/token"))
                                return res.sendString(Mono.just("{\"access_token\":\"token\","
                                        + "\"expires_in\":120,\"token_type\":\"bearer\"}"));
                            if (path.equals("/list")) result = "[{\"id\":\"user\"}]";
                            else if (path.equals("/member")) result = "{\"id\":\"user\"}";
                            else if (path.equals("/empty")) result = "null";
                            else if (path.equals("/page") || path.equals("/nested-page")) {
                                Query query = Json.convert(Json.parse(body.getBytes(StandardCharsets.UTF_8)), Query.class);
                                String row = "{\"id\":\"user-" + query.pageIndex + "\"}";
                                if (path.equals("/nested-page")) row = "[" + row + "]";
                                result = "{\"total\":2,\"pageSize\":1,\"data\":[" + row + "]}";
                            } else if (path.endsWith("/events"))
                                result = "[{\"id\":\"source\",\"name\":\"Source\",\"events\":["
                                        + "{\"id\":\"application.updated\",\"version\":\"1\",\"schema\":{}}]}]";
                            else if (path.endsWith("/channels")) result = "[{\"id\":\"http-hook\"}]";
                            else if (path.endsWith("/_validate"))
                                result = "{\"valid\":false,\"issues\":[{\"field\":\"scopes\",\"code\":\"required\",\"message\":\"Required\"}]}";
                            else if (path.endsWith("/records/_query"))
                                result = "{\"total\":1,\"data\":[{\"recordId\":\"record\",\"attempt\":2,\"result\":\"success\"}]}";
                            else if (path.endsWith("/tasks/_query"))
                                result = "{\"total\":1,\"data\":[{\"id\":\"task\",\"retryAttempt\":1,\"state\":\"completed\"}]}";
                            else if (path.endsWith("/payload"))
                                result = "{\"rawBody\":\"{ \\\"id\\\":\\\"evt\\\" }\",\"body\":{\"id\":\"evt\"},\"attempt\":2}";
                            else if (path.endsWith("/subscriptions/_query"))
                                result = "{\"total\":1,\"data\":[{\"id\":\"sub\",\"channelProvider\":\"http-hook\"}]}";
                            else if (path.endsWith("/subscriptions") && req.method().name().equals("POST")) {
                                SubscriptionConfig config = Json.convert(
                                        Json.parse(body.getBytes(StandardCharsets.UTF_8)), SubscriptionConfig.class);
                                assertEquals(EventTypes.APPLICATION_UPDATED, config.scopes.get(0).eventTypes.get(0));
                                result = "\"sub\"";
                            } else if (path.contains("/subscriptions/")) {
                                result = req.method().name().equals("GET")
                                        ? "{\"id\":\"sub\",\"scopes\":[{\"eventTypes\":[\"application.updated\"]}],\"state\":\"disabled\"}"
                                        : "1";
                            } else if (path.endsWith("/asset-accesses"))
                                result = "[{\"assetType\":\"device\",\"accesses\":[{\"supportId\":\"fixed\",\"ids\":[\"device-1\"]}]}]";
                            else if (path.equals("/role/_query"))
                                result = "{\"total\":1,\"data\":[{\"id\":\"role\",\"state\":{\"value\":\"enabled\",\"text\":\"Enabled\"}}]}";
                            else if (path.endsWith("/user-roles/_query"))
                                result = "{\"total\":1,\"data\":[{\"userId\":\"user\",\"roleId\":\"role\",\"relationTime\":123}]}";
                            else if (path.endsWith("/api-grants/_query"))
                                result = "{\"total\":1,\"data\":[{\"groupId\":\"api-group\",\"operationIds\":[\"read\"]}]}";
                            else if (path.endsWith("/authorization/_query"))
                                result = "{\"total\":1,\"data\":[{\"permission\":\"device\",\"actions\":[\"query\"],\"dataAccesses\":[{\"type\":\"custom\",\"config\":{\"region\":\"east\"}}]}]}";
                            else if (path.endsWith("/menus/_query"))
                                result = "{\"total\":1,\"data\":[{\"menuId\":\"menu\",\"buttons\":[{\"id\":\"query\",\"granted\":true}]}]}";
                            else result = "{\"total\":1,\"data\":[{\"id\":\"user\",\"region\":\"east\"}]}";
                            return res.header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"status\":200,\"result\":" + result + "}"));
                        })).bindNow();
    }

    @Test
    void bothClientsDecodeClassesAndNestedGenericRequestsStreamsAndPages() {
        DisposableServer server = server();
        try (JetLinksReactiveClient reactive = new JetLinksReactiveClient(
                "http://127.0.0.1:" + server.port(), TokenProvider.anonymous());
                JetLinksClient client = new JetLinksClient(reactive)) {
            TypeRef<List<Member>> list = new TypeRef<List<Member>>() {};
            assertEquals("user", reactive.request(ApiRequest.get("/member"), Member.class).block().id);
            assertEquals("user", client.request(ApiRequest.get("/member"), Member.class).id);
            assertEquals("user", reactive.request(ApiRequest.get("/list"), list).block().get(0).id);
            assertEquals("user", client.request(ApiRequest.get("/list"), list).get(0).id);
            assertNull(reactive.request(ApiRequest.get("/empty"), Member.class).block());
            assertNull(client.request(ApiRequest.get("/empty"), Void.class));
            assertEquals(2, reactive.stream(ApiRequest.get("/stream"), Member.class).collectList().block().size());
            assertEquals("user", reactive.stream(ApiRequest.get("/nested-stream"), list).blockFirst().get(0).id);
            try (Stream<Member> values = client.stream(ApiRequest.get("/stream"), Member.class)) {
                assertEquals(2, values.count());
            }
            try (Stream<List<Member>> values = client.stream(ApiRequest.get("/nested-stream"), list)) {
                assertEquals("user", values.findFirst().get().get(0).id);
            }
            Query query = new Query().page(0, 50);
            List<Member> members = reactive.pages("/page", query, Member.class).collectList().block();
            assertEquals(2, members.size());
            assertEquals("user-1", members.get(1).id);
            assertEquals(0, query.pageIndex);
            assertEquals("user-1", reactive.pages("/nested-page", query, list).collectList().block().get(1).get(0).id);
            try (Stream<Member> values = client.pages("/page", query, Member.class)) {
                assertEquals(2, values.count());
            }
            try (Stream<List<Member>> values = client.pages("/nested-page", query, list)) {
                assertEquals(2, values.count());
            }
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void subscriptionFacadesMatchScalarCatalogPageAndDeliveryContracts() {
        DisposableServer server = server();
        try (JetLinksReactiveClient client = new JetLinksReactiveClient(
                "http://127.0.0.1:" + server.port(), TokenProvider.anonymous())) {
            Subscriptions subscriptions = new Subscriptions(client, "app");
            SubscriptionConfig config = new SubscriptionConfig();
            config.name = "Test";
            config.channelProvider = DeliveryChannel.HTTP_HOOK;
            SubscriptionScope scope = new SubscriptionScope();
            scope.eventTypes.add(EventTypes.APPLICATION_UPDATED);
            config.scopes.add(scope);
            assertEquals("sub", subscriptions.create(config).block());
            assertEquals(1, subscriptions.update("sub", config).block().intValue());
            assertEquals(1, subscriptions.enable("sub", true).block().intValue());
            assertEquals(1, subscriptions.enable("sub", false).block().intValue());
            assertEquals(1, subscriptions.delete("sub").block().intValue());
            assertEquals(Subscription.DISABLED, subscriptions.get("sub").block().state);
            assertEquals(EventTypes.APPLICATION_UPDATED,
                    subscriptions.get("sub").block().scopes.get(0).eventTypes.get(0));
            assertEquals("sub", subscriptions.query(new Query()).block().data.get(0).id);
            assertEquals("scopes", subscriptions.validate(config).block().issues.get(0).field);
            assertFalse(subscriptions.validate(config).block().valid);
            assertEquals(EventTypes.APPLICATION_UPDATED,
                    subscriptions.events().block().get(0).events.get(0).id);
            assertEquals(DeliveryChannel.HTTP_HOOK, subscriptions.channels().block().get(0).id);
            assertEquals("task", subscriptions.tasks("sub", new Query()).block().data.get(0).id);
            assertEquals(2, subscriptions.records("sub", new Query()).block().data.get(0).attempt.intValue());
            DeliveryPayload payload = subscriptions.payload("sub", "task", 2).block();
            assertEquals("evt", payload.body.id);
            assertEquals("{ \"id\":\"evt\" }", payload.rawBody);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void businessFactsAndOAuthExposeModels() {
        DisposableServer server = server();
        String base = "http://127.0.0.1:" + server.port();
        try (JetLinksReactiveClient client = new JetLinksReactiveClient(base, TokenProvider.anonymous());
                OAuth2 oauth = new OAuth2(base, "key", "secret", true)) {
            BusinessApplications business = new BusinessApplications(client, "app");
            assertEquals("east", business.members().blockFirst().extensions().get("region"));
            assertEquals("enabled", business.roles().blockFirst().state.value);
            assertEquals("enabled", Json.convert("enabled", EnumValue.class).value);
            assertEquals("role", business.userRoles().blockFirst().roleId);
            assertTrue(business.grants().blockFirst().operationIds.contains("read"));
            assertEquals("east", business.roleAuthorization("role").blockFirst()
                    .dataAccesses.get(0).config.get("region"));
            assertTrue(business.roleMenus("role").blockFirst().buttons.get(0).granted);
            assertEquals("device", business.assets("role").block().get(0).assetType);
            OAuth2Token token = oauth.machine(null).block();
            assertEquals("token", token.accessToken);
            assertEquals(120, token.expiresIn);
            assertEquals("token", oauth.exchange("code", "https://app/callback", "verifier").block().accessToken);
            try (OAuth2 empty = new OAuth2(base + "/empty-token", "key", "secret", true)) {
                assertThrows(IllegalStateException.class, () -> empty.machine(null).block());
            }
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void typedCommandParametersUseTrustedIdentityAndGenericPayloads() {
        CommandTest fixture = new CommandTest();
        AtomicReference<Parameters> seen = new AtomicReference<>();
        CommandService commands = new CommandService((p, a) -> Mono.just(fixture.state()), 1)
                .register("Typed", false, Parameters.class, (application, parameters) -> {
                    seen.set(parameters);
                    return Mono.just(parameters);
                })
                .register("Generic", true, new TypeRef<Payload<List<Member>>>() {},
                        (application, parameters) -> Flux.fromIterable(parameters.value));
        String target = "/api/commands/1/Typed";
        CommandService.Response response = commands.handle("POST", target,
                fixture.headers("POST", target),
                "{\"applicationId\":\"spoof\",\"templateId\":\"spoof\",\"count\":3}".getBytes(StandardCharsets.UTF_8)).block();
        assertEquals(200, response.status);
        response.body.collectList().block();
        response.close();
        assertEquals("app", seen.get().applicationId);
        assertEquals("template", seen.get().templateId);
        assertEquals(3, seen.get().count);
        target = "/api/commands/1/Generic";
        response = commands.handle("POST", target, fixture.headers("POST", target),
                "{\"value\":[{\"id\":\"user\"}]}".getBytes(StandardCharsets.UTF_8)).block();
        assertEquals("user", Json.read(response.body.blockFirst(), TypeRef.of(Member.class)).id);
        response.close();
        CommandService.Response caps = commands.handle("GET", "/api/capabilities",
                fixture.headers("GET", "/api/capabilities"), new byte[0]).block();
        List<CommandCapabilities> declarations = Json.read(caps.body.blockFirst(),
                new TypeRef<List<CommandCapabilities>>() {});
        assertEquals(CommandCapabilities.VERSION_1, declarations.get(0).version);
        assertEquals(Arrays.asList("Generic", "Typed"), declarations.get(0).capabilities);
        caps.close();
        assertEquals(0, commands.inFlight());
    }
}
