package org.jetlinks.sdk.client.example;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.auth.*;
import org.jetlinks.sdk.client.command.*;
import org.jetlinks.sdk.client.crypto.ProtocolHeaders;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.model.*;
import org.jetlinks.sdk.client.notification.*;
import org.jetlinks.sdk.client.subscription.SubscriptionConfig;

import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 共用的真实接入示例流程，两种 HTTP 启动方式都委托此类，平台协议统一使用 sdk-client-core。
 *
 * @since 2.0
 */
public final class ExampleService implements AutoCloseable {
    public final ExampleConfig config;
    public final ExampleStore store;
    private final NotificationVerifier verifier;
    private final CommandService commands;
    private final Map<String, Handle> clients = new ConcurrentHashMap<>();
    private final Map<String, AuthorizationSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, SnapshotSynchronizer<AuthorizationSnapshot>> synchronizers =
            new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Deque<ObjectNode> commandRecords = new ArrayDeque<>();
    private Disposable worker;
    private volatile String syncStatus = "idle";

    public ExampleService(ExampleConfig config) {
        this.config = config;
        store = new ExampleStore(config.database, config::privateKey);
        verifier = new NotificationVerifier(this::resolveKey);
        commands =
                new CommandService((platform, app) -> store.find(platform, app), 8)
                        .register(
                                "GetMetrics",
                                false,
                                (a, n) ->
                                        Mono.just(
                                                Collections.singletonList(
                                                        map(
                                                                "id",
                                                                "requests",
                                                                "name",
                                                                "Example requests",
                                                                "unit",
                                                                "count"))))
                        .register(
                                "ReadUsage",
                                true,
                                (a, n) ->
                                        Flux.just(
                                                map(
                                                        "metricId",
                                                        "requests",
                                                        "value",
                                                        commandRecordsSize(),
                                                        "timestamp",
                                                        System.currentTimeMillis())))
                        .register("Echo", false, (a, n) -> Mono.just(n))
                        .register(
                                "WatchUsage",
                                true,
                                WatchUsage.class,
                                (a, n) ->
                                        Flux.interval(Duration.ofMillis(200))
                                                .take(Math.min(100, Math.max(1, n.limit)))
                                                .map(
                                                        i ->
                                                                map(
                                                                        "index",
                                                                        i,
                                                                        "timestamp",
                                                                        System
                                                                                .currentTimeMillis())));
    }

    /** 服务边界拥有的单一同步任务；合并通知触发，事实查询失败时在后续轮次重试。 */
    public void start() {
        worker =
                Flux.interval(Duration.ZERO, Duration.ofSeconds(5))
                        .onBackpressureDrop()
                        .concatMap(this::refreshDirtyApplications)
                        .subscribe(v -> {}, e -> syncStatus = "worker failed");
    }

    private Mono<Void> refreshDirtyApplications(long tick) {
        return store.applications()
                .flatMapMany(Flux::fromIterable)
                .filter(state -> state.active() && (tick % 6 == 0 || dirty.remove(context(state))))
                .flatMap(this::refreshOneApplication, 4)
                .then()
                .onErrorResume(
                        error -> {
                            syncStatus = "state lookup failed";
                            return Mono.empty();
                        });
    }

    private Mono<AuthorizationSnapshot> refreshOneApplication(ApplicationState state) {
        String id = context(state);
        return synchronize(id, state)
                .onErrorResume(
                        error -> {
                            dirty.add(id);
                            syncStatus = "failed";
                            return Mono.empty();
                        });
    }

    private Mono<TrustedKey> resolveKey(String platform, String app, String keyId) {
        if (platform.isEmpty()) return resolveApiKey(null, platform, app, keyId);
        return store.find(platform, app)
                .flatMap(state -> templateKey(platform, app, keyId, state.templateId))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        store.find(platform, app)
                                                .hasElement()
                                                .flatMap(
                                                        known ->
                                                                known
                                                                        ? Mono.empty()
                                                                        : templateKey(
                                                                                platform, app,
                                                                                keyId, null))));
    }

    private Mono<TrustedKey> resolveApiKey(
            String channel, String platform, String app, String keyId) {
        if (!platform.isEmpty()) return Mono.empty();
        List<ExampleConfig.ApiApplication> matches = new ArrayList<>();
        for (ExampleConfig.ApiApplication c : config.apiApplications) {
            if ((channel == null || channel.equals(c.contextId))
                    && app.equals(c.applicationId)
                    && keyId.equals(c.accessKey)) matches.add(c);
        }
        if (matches.size() != 1) return Mono.empty();
        ExampleConfig.ApiApplication c = matches.get(0);
        return Mono.just(TrustedKey.api(app, c.accessSecret, c.contextId));
    }

    private Mono<TrustedKey> templateKey(
            String platform, String app, String keyId, String knownTemplate) {
        for (ExampleConfig.Template t : config.templates)
            if (platform.equals(t.platformId)
                    && keyId.equals(t.notificationKeyId)
                    && (knownTemplate == null || knownTemplate.equals(t.templateId)))
                return Mono.just(
                        TrustedKey.business(platform, t.templateId, app, t.notificationSecret));
        return Mono.empty();
    }

    public Mono<ExampleResponse> handle(
            String method, String target, Map<String, List<String>> headers, byte[] body) {
        return Mono.defer(() -> route(method, target, headers, body))
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ExampleResponse.error(
                                                errorStatus(error),
                                                "Operation failed; check access and"
                                                        + " configuration")));
    }

    // 响应只暴露分类后的状态，异常中的远端正文和凭据不进入前端。
    private static int errorStatus(Throwable error) {
        if (error instanceof SdkException) {
            SdkException failure = (SdkException) error;
            switch (failure.code) {
                case AUTHENTICATION:
                    return 401;
                case INVALID_ARGUMENT:
                case SERIALIZATION:
                    return 400;
                case LIMIT_EXCEEDED:
                    return 413;
                case REMOTE_ERROR:
                    return failure.status;
                default:
                    return 502;
            }
        }
        if (error instanceof SecurityException) return 401;
        if (error instanceof IllegalArgumentException) return 400;
        return 502;
    }

    private static boolean rejectedNotification(Throwable error) {
        return errorStatus(error) == 401
                || errorStatus(error) == 400
                || error instanceof SdkException
                        && ((SdkException) error).code == Code.CRYPTOGRAPHY;
    }

    private Mono<ExampleResponse> route(
            String method, String target, Map<String, List<String>> headers, byte[] body) {
        URI uri = URI.create(target);
        String path = uri.getPath();
        Map<String, String> query = query(uri.getRawQuery());
        if ("POST".equals(method)
                && ("/jetlinks/events".equals(path) || path.startsWith("/jetlinks/events/"))) {
            NotificationVerifier selectedVerifier =
                    path.equals("/jetlinks/events")
                            ? verifier
                            : new NotificationVerifier(
                                    (p, a, k) ->
                                            resolveApiKey(
                                                    path.substring("/jetlinks/events/".length()),
                                                    p,
                                                    a,
                                                    k));
            return selectedVerifier
                    .verify(headers, body)
                    .flatMap(this::acceptNotification)
                    .onErrorResume(
                            e ->
                                    rejectedNotification(e)
                                            ? store.rejected(body)
                                                    .thenReturn(
                                                            ExampleResponse.error(
                                                                    401, "Notification rejected"))
                                            : Mono.just(
                                                    ExampleResponse.error(
                                                            errorStatus(e) == 413 ? 413 : 503,
                                                            "Durable receipt failed")));
        }
        if (path.equals("/api/capabilities") || path.startsWith("/api/commands/1/")) {
            return commands.handle(method, target, headers, body)
                    .map(
                            r -> {
                                ObjectNode record = Json.MAPPER.createObjectNode();
                                record.put("requestId", r.headers.get(ProtocolHeaders.REQUEST_ID))
                                        .put(
                                                "platformId",
                                                r.headers.get(ProtocolHeaders.PLATFORM_ID))
                                        .put(
                                                "applicationId",
                                                r.headers.get(ProtocolHeaders.APPLICATION_ID))
                                        .put("target", target)
                                        .put("status", r.status)
                                        .put("receivedAt", System.currentTimeMillis());
                                ArrayNode results = record.putArray("results");
                                addCommand(record);
                                return new ExampleResponse(
                                        r.status,
                                        r.headers,
                                        r.body.doOnNext(
                                                        bytes -> {
                                                            synchronized (ExampleService.this) {
                                                                if (results.size() < 100)
                                                                    results.add(
                                                                            redact(
                                                                                    Json.parse(
                                                                                            bytes)));
                                                            }
                                                        })
                                                .doFinally(
                                                        s -> {
                                                            synchronized (ExampleService.this) {
                                                                record.put("completion", s.name());
                                                            }
                                                            r.close();
                                                        }));
                            });
        }
        if (path.equals("/example-api/info"))
            return Mono.just(
                    ExampleResponse.json(
                            map(
                                    "language",
                                    "java",
                                    "sdkVersion",
                                    "2.0.0-SNAPSHOT",
                                    "features",
                                    Arrays.asList(
                                            "requests",
                                            "notifications",
                                            "login",
                                            "authorization",
                                            "subscriptions",
                                            "commands",
                                            "diagnostics"),
                                    "syncStatus",
                                    syncStatus)));
        if (path.equals("/example-api/applications"))
            return store.applications()
                    .map(
                            list -> {
                                ArrayNode out = Json.MAPPER.createArrayNode();
                                for (ExampleConfig.ApiApplication c : config.apiApplications)
                                    out.add(publicState(c.contextId, c.state()));
                                for (ApplicationState s : list) out.add(publicState(context(s), s));
                                return ExampleResponse.json(out);
                            });
        if (path.startsWith("/example-api/notifications")) {
            String id =
                    path.equals("/example-api/notifications")
                            ? null
                            : path.substring("/example-api/notifications/".length());
            return store.receipts(id, Integer.parseInt(query.getOrDefault("pageIndex", "0")), query)
                    .map(n -> ExampleResponse.json(redact(n)));
        }
        if (path.equals("/example-api/login/callback")) return callback(query, headers);
        if (path.equals("/example-api/session")) {
            String cookie = cookie(headers, "jl-session");
            Session session = sessions.get(cookie);
            if ("DELETE".equals(method)) {
                sessions.remove(cookie);
                return Mono.just(
                        new ExampleResponse(
                                200,
                                mapString(
                                        "Content-Type",
                                        "application/json",
                                        "Set-Cookie",
                                        "jl-session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"),
                                Flux.just(Json.bytes(map("authenticated", false)))));
            }
            if (session == null || session.expires < System.currentTimeMillis()) {
                sessions.remove(cookie);
                return Mono.just(ExampleResponse.json(map("authenticated", false)));
            }
            return Mono.just(
                    ExampleResponse.json(
                            map(
                                    "authenticated",
                                    true,
                                    "contextId",
                                    session.context,
                                    "context",
                                    session.contextData)));
        }
        if (path.startsWith("/example-api/applications/")) {
            String[] parts = path.substring("/example-api/applications/".length()).split("/", 2);
            String context = parts[0], action = parts.length == 1 ? "" : parts[1];
            return state(context)
                    .switchIfEmpty(
                            Mono.error(
                                    () ->
                                            new SdkException(
                                                    Code.INVALID_ARGUMENT, "Unknown application")))
                    .flatMap(s -> application(method, context, action, s, body));
        }
        if ("GET".equals(method) && !path.startsWith("/example-api/")) return staticFile(path);
        return Mono.just(ExampleResponse.error(404, "Unknown endpoint"));
    }

    private Mono<ExampleResponse> acceptNotification(VerifiedNotification notice) {
        return store.find(notice.platformId, notice.applicationId)
                .defaultIfEmpty(new ApplicationState())
                .flatMap(
                        before ->
                                store.accept(notice)
                                        .flatMap(
                                                fresh ->
                                                        store.find(
                                                                        notice.platformId,
                                                                        notice.applicationId)
                                                                .defaultIfEmpty(
                                                                        new ApplicationState())
                                                                .map(
                                                                        after -> {
                                                                            String id =
                                                                                    "business-"
                                                                                            + ExampleStore
                                                                                                    .identity(
                                                                                                            notice.platformId,
                                                                                                            notice.applicationId);
                                                                            if (fresh) {
                                                                                dirty.add(id);
                                                                                if (!stateIdentity(
                                                                                                before)
                                                                                        .equals(
                                                                                                stateIdentity(
                                                                                                        after))) {
                                                                                    clients.entrySet()
                                                                                            .removeIf(
                                                                                                    entry -> {
                                                                                                        if (!entry.getKey()
                                                                                                                .startsWith(
                                                                                                                        id
                                                                                                                                + ":"))
                                                                                                            return false;
                                                                                                        entry.getValue()
                                                                                                                .close();
                                                                                                        return true;
                                                                                                    });
                                                                                    synchronizers
                                                                                            .remove(
                                                                                                    id);
                                                                                }
                                                                            }
                                                                            return new ExampleResponse(
                                                                                    204,
                                                                                    Collections
                                                                                            .emptyMap(),
                                                                                    Flux.empty());
                                                                        })));
    }

    private Mono<ExampleResponse> application(
            String method, String context, String action, ApplicationState state, byte[] raw) {
        ExampleOperation input =
                raw.length == 0
                        ? new ExampleOperation()
                        : Json.read(raw, TypeRef.of(ExampleOperation.class));
        if (action.isEmpty()) return Mono.just(ExampleResponse.json(publicState(context, state)));
        if (action.equals("login")) return login(context, state, input);
        if (action.equals("commands"))
            return Mono.just(
                    ExampleResponse.json(
                            map(
                                    "capabilities",
                                    commands.capabilities(state),
                                    "records",
                                    commandRecords(state))));
        Handle handle = client(context, state, input.authMode);
        if (action.equals("diagnostics"))
            return Mono.just(
                    ExampleResponse.json(
                            map(
                                    "tokenRefreshes",
                                    handle.provider == null ? 0 : handle.provider.refreshCount(),
                                    "tokenCacheHits",
                                    handle.provider == null ? 0 : handle.provider.cacheHits(),
                                    "commandsInFlight",
                                    commands.inFlight(),
                                    "syncStatus",
                                    syncStatus,
                                    "snapshotAvailable",
                                    snapshots.containsKey(context))));
        if (action.equals("authorization")) {
            BusinessApplications business =
                    new BusinessApplications(handle.client, state.applicationId);
            String op = input.operation, role = input.roleId;
            if ("roleAuthorization".equals(op))
                return business.roleAuthorization(role)
                        .take(10000)
                        .collectList()
                        .map(ExampleResponse::json);
            if ("roleMenus".equals(op))
                return business.roleMenus(role)
                        .take(10000)
                        .collectList()
                        .map(ExampleResponse::json);
            if ("assets".equals(op)) return business.assets(role).map(ExampleResponse::json);
            Mono<AuthorizationSnapshot> snapshot =
                    "POST".equals(method)
                            ? synchronize(context, state)
                            : Mono.justOrEmpty(snapshots.get(context));
            return snapshot.map(ExampleResponse::json)
                    .defaultIfEmpty(ExampleResponse.json(map("state", "not synchronized")));
        }
        if (action.equals("requests")) {
            ApiRequest request = ApiRequest.of(input.method, input.path);
            if (input.body != null) request.body(input.body);
            if (input.stream)
                return Mono.just(
                        new ExampleResponse(
                                200,
                                Collections.singletonMap("Content-Type", "application/x-ndjson"),
                                handle.client.stream(request)
                                        .map(
                                                n ->
                                                        (new String(
                                                                                Json.bytes(
                                                                                        redact(n)),
                                                                                StandardCharsets
                                                                                        .UTF_8)
                                                                        + "\n")
                                                                .getBytes(
                                                                        StandardCharsets.UTF_8))));
            long begin = System.currentTimeMillis();
            Mono<JsonNode> result =
                    "sync".equals(input.client)
                            ? Mono.fromCallable(
                                            () ->
                                                    new JetLinksClient(handle.client)
                                                            .request(request))
                                    .subscribeOn(Schedulers.boundedElastic())
                            : handle.client.request(request);
            return result.map(
                    n ->
                            ExampleResponse.json(
                                    map(
                                            "durationMs",
                                            System.currentTimeMillis() - begin,
                                            "result",
                                            redact(n))));
        }
        if (action.equals("subscriptions")) {
            Handle management =
                    client(
                            context,
                            state,
                            managementToken(context) == null ? input.authMode : "management");
            Subscriptions subscriptions = new Subscriptions(management.client, state.applicationId);
            String op = input.operation, id = input.id;
            Mono<?> result;
            switch (op) {
                case "create":
                    result =
                            subscriptions.create(
                                    Json.convert(input.body, SubscriptionConfig.class));
                    break;
                case "update":
                    result =
                            subscriptions.update(
                                    id, Json.convert(input.body, SubscriptionConfig.class));
                    break;
                case "validate":
                    result =
                            subscriptions.validate(
                                    Json.convert(input.body, SubscriptionConfig.class));
                    break;
                case "enable":
                    result = subscriptions.enable(id, true);
                    break;
                case "disable":
                    result = subscriptions.enable(id, false);
                    break;
                case "delete":
                    result = subscriptions.delete(id);
                    break;
                case "get":
                    result = subscriptions.get(id);
                    break;
                case "events":
                    result = subscriptions.events();
                    break;
                case "channels":
                    result = subscriptions.channels();
                    break;
                case "tasks":
                    result = subscriptions.tasks(id, new Query());
                    break;
                case "records":
                    result = subscriptions.records(id, new Query());
                    break;
                case "payload":
                    result = subscriptions.payload(id, input.taskId, input.attempt);
                    break;
                default:
                    result = subscriptions.query(new Query());
            }
            return result.map(n -> ExampleResponse.json(redact(Json.tree(n))));
        }
        return Mono.just(ExampleResponse.error(404, "Unknown application operation"));
    }

    private Mono<AuthorizationSnapshot> synchronize(String id, ApplicationState state) {
        if (!state.active())
            return Mono.error(() -> new SdkException(Code.INVALID_STATE, "Inactive application"));
        SnapshotSynchronizer<AuthorizationSnapshot> sync =
                synchronizers.computeIfAbsent(
                        id, key -> new SnapshotSynchronizer<>(() -> loadSnapshot(id).flux(), 1));
        return sync.refresh()
                .map(
                        values -> {
                            AuthorizationSnapshot snapshot = values.get(0);
                            snapshots.put(id, snapshot);
                            syncStatus = "synchronized";
                            return snapshot;
                        });
    }

    private Mono<AuthorizationSnapshot> loadSnapshot(String id) {
        return state(id)
                .flatMap(
                        current -> {
                            BusinessApplications business =
                                    new BusinessApplications(
                                            client(id, current, "oauth2").client,
                                            current.applicationId);
                            return Mono.zip(
                                            snapshotPart(business.members()),
                                            snapshotPart(business.roles()),
                                            snapshotPart(business.userRoles()),
                                            snapshotPart(business.grants()))
                                    .map(
                                            parts ->
                                                    new AuthorizationSnapshot(
                                                            parts.getT1(),
                                                            parts.getT2(),
                                                            parts.getT3(),
                                                            parts.getT4()));
                        });
    }

    private <T> Mono<List<T>> snapshotPart(Flux<T> source) {
        // 多读取一条检测容量溢出，避免把截断数据发布为完整快照。
        return source.take(10001)
                .collectList()
                .map(
                        values -> {
                            if (values.size() > 10000)
                                throw new SdkException(
                                        Code.LIMIT_EXCEEDED, "Example snapshot exceeds limit");
                            return values;
                        });
    }

    public static class WatchUsage {
        public int limit = 5;
    }

    public static class AuthorizationSnapshot {
        public final List<Member> members;
        public final List<Role> roles;
        public final List<UserRole> userRoles;
        public final List<ApiGrant> apiGrants;
        public final long synchronizedAt = System.currentTimeMillis();

        AuthorizationSnapshot(
                List<Member> members,
                List<Role> roles,
                List<UserRole> userRoles,
                List<ApiGrant> apiGrants) {
            this.members = members;
            this.roles = roles;
            this.userRoles = userRoles;
            this.apiGrants = apiGrants;
        }
    }

    private Mono<ExampleResponse> login(
            String context, ApplicationState state, ExampleOperation input) {
        String userToken = userToken(context, state);
        if (userToken == null)
            return Mono.just(
                    ExampleResponse.error(
                            400,
                            "Configure a platform userToken to run the signed-in platform"
                                    + " authorization step"));
        if (pending.size() > 128)
            pending.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
        if (pending.size() >= 128)
            return Mono.just(ExampleResponse.error(429, "Too many pending logins"));
        OAuth2 oauth = new OAuth2(state.apiBaseUrl, state.accessKey, state.accessSecret, true);
        String redirect =
                config.publicBaseUrl.replaceAll("/+$", "") + "/example-api/login/callback";
        OAuth2.Authorization auth = oauth.authorization(redirect, null);
        String cookie = UUID.randomUUID().toString();
        // 原始平台入口用于协议演示；生产登录建议统一走带 state/PKCE 的授权。
        boolean platform = input.entry.equals("platform");
        PendingLogin flow = new PendingLogin(context, state.copy(), auth, platform);
        pending.put(cookie, flow);
        ApiRequest request =
                platform
                        ? ApiRequest.get(
                                "/business-application/"
                                        + ApiRequest.segment(state.applicationId)
                                        + "/_redirect")
                        : ApiRequest.get("/oauth2/authorize")
                                .query("response_type", "code")
                                .query("client_id", state.accessKey)
                                .query("redirect_uri", redirect)
                                .query("state", auth.state)
                                .query("code_challenge", challenge(auth.verifier))
                                .query("code_challenge_method", "S256");
        JetLinksReactiveClient user =
                new JetLinksReactiveClient(state.apiBaseUrl, TokenProvider.bearer(userToken));
        Mono<String> location =
                platform
                        ? user.request(request, ApplicationRedirect.class)
                                .map(result -> result.location)
                        : user.request(request, String.class);
        return location.map(
                        address -> {
                            return new ExampleResponse(
                                    200,
                                    mapString(
                                            "Content-Type",
                                            "application/json",
                                            "Set-Cookie",
                                            "jl-pending="
                                                    + cookie
                                                    + "; HttpOnly; SameSite=Lax; Path=/;"
                                                    + " Max-Age=600"),
                                    Flux.just(Json.bytes(map("url", address))));
                        })
                .doFinally(
                        s -> {
                            user.close();
                            oauth.close();
                        });
    }

    private String challenge(String verifier) {
        try {
            return Signatures.base64(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot compute login challenge", e);
        }
    }

    private Mono<ExampleResponse> callback(
            Map<String, String> query, Map<String, List<String>> headers) {
        PendingLogin flow = pending.remove(cookie(headers, "jl-pending"));
        if (flow == null
                || flow.expires < System.currentTimeMillis()
                || !flow.platform && !flow.auth.state.equals(query.get("state")))
            return Mono.error(() -> new SdkException(Code.AUTHENTICATION, "Invalid login state"));
        // 标准授权只保证 code/state，应用身份由已绑定浏览器的待登录记录确定。
        // 平台 _redirect 才追加来源和应用参数，须与本次发起记录一致。
        if (flow.platform
                && flow.state.templateId != null
                && (!flow.state.platformId.equals(query.get("platformId"))
                        || !flow.state.applicationId.equals(query.get("applicationId"))))
            return Mono.error(
                    () -> new SdkException(Code.AUTHENTICATION, "Callback identity mismatch"));
        OAuth2 oauth =
                new OAuth2(
                        flow.state.apiBaseUrl, flow.state.accessKey, flow.state.accessSecret, true);
        return oauth.exchange(
                        query.get("code"),
                        flow.auth.redirectUri,
                        flow.platform ? null : flow.auth.verifier)
                .flatMap(
                        token -> {
                            JetLinksReactiveClient user =
                                    new JetLinksReactiveClient(
                                            flow.state.apiBaseUrl,
                                            TokenProvider.bearer(token.accessToken));
                            return user.request(
                                            ApiRequest.get("/authorize/context"),
                                            AuthorizationContext.class)
                                    .map(
                                            ctx -> {
                                                if (!flow.state.applicationId.equals(
                                                                ctx.application == null
                                                                        ? null
                                                                        : ctx.application.id)
                                                        || ctx.subject == null
                                                        || ctx.subject.id == null
                                                        || ctx.subject.id.isEmpty())
                                                    throw new SdkException(
                                                            Code.AUTHENTICATION,
                                                            "Authorization context mismatch");
                                                sessions.entrySet()
                                                        .removeIf(
                                                                e ->
                                                                        e.getValue().expires
                                                                                < System
                                                                                        .currentTimeMillis());
                                                if (sessions.size() >= 128)
                                                    throw new SdkException(
                                                            Code.INVALID_STATE, "Session limit");
                                                String id = UUID.randomUUID().toString();
                                                sessions.put(
                                                        id,
                                                        new Session(
                                                                flow.context,
                                                                ctx,
                                                                System.currentTimeMillis()
                                                                        + Math.min(
                                                                                        900,
                                                                                        token.expiresIn)
                                                                                * 1000));
                                                return ExampleResponse.redirect(
                                                        "/",
                                                        "jl-session="
                                                                + id
                                                                + "; HttpOnly; SameSite=Lax;"
                                                                + " Path=/; Max-Age=900");
                                            })
                                    .doFinally(s -> user.close());
                        })
                .doFinally(s -> oauth.close());
    }

    private Mono<ApplicationState> state(String context) {
        for (ExampleConfig.ApiApplication a : config.apiApplications)
            if (context.equals(a.contextId)) return Mono.just(a.state());
        return store.applications()
                .flatMapIterable(v -> v)
                .filter(s -> context.equals(context(s)))
                .next();
    }

    private String context(ApplicationState s) {
        return "business-" + ExampleStore.identity(s.platformId, s.applicationId);
    }

    private Handle client(String context, ApplicationState state, String mode) {
        if (!state.active())
            throw new SdkException(
                    Code.INVALID_ARGUMENT, "Application is inactive or credentials are missing");
        if (clients.size() >= 128 && !clients.containsKey(context + ":" + mode))
            throw new SdkException(Code.LIMIT_EXCEEDED, "Example client capacity reached");
        String fingerprint =
                Signatures.sha256(
                        Json.bytes(
                                Arrays.asList(
                                        state.apiBaseUrl,
                                        state.accessKey,
                                        state.accessSecret,
                                        state.keyRevision,
                                        mode)));
        return clients.compute(
                context + ":" + mode,
                (k, old) -> {
                    if (old != null && old.fingerprint.equals(fingerprint)) return old;
                    if (old != null) old.close();
                    OAuth2 oauth = null;
                    CachedTokenProvider provider = null;
                    TokenProvider auth;
                    if ("management".equals(mode))
                        auth =
                                TokenProvider.bearer(
                                        Objects.requireNonNull(managementToken(context)));
                    else if ("user".equals(mode))
                        auth =
                                TokenProvider.bearer(
                                        Objects.requireNonNull(userToken(context, state)));
                    else if ("local".equals(mode))
                        auth =
                                provider =
                                        OAuth2.localTokens(
                                                state.accessKey,
                                                state.accessSecret,
                                                null,
                                                Duration.ofMinutes(2));
                    else if ("oauth2".equals(mode)) {
                        oauth =
                                new OAuth2(
                                        state.apiBaseUrl,
                                        state.accessKey,
                                        state.accessSecret,
                                        true);
                        auth = provider = oauth.machineTokens(null);
                    } else throw new SdkException(Code.INVALID_ARGUMENT, "Unknown authMode");
                    return new Handle(
                            fingerprint,
                            new JetLinksReactiveClient(state.apiBaseUrl, auth),
                            oauth,
                            provider);
                });
    }

    private String stateIdentity(ApplicationState s) {
        return Signatures.sha256(
                Json.bytes(
                        Arrays.asList(
                                s.apiBaseUrl,
                                s.accessKey,
                                s.accessSecret,
                                s.keyRevision,
                                s.enabled,
                                s.deletedAt)));
    }

    private String managementToken(String context) {
        for (ExampleConfig.ApiApplication a : config.apiApplications)
            if (context.equals(a.contextId)) return a.managementToken;
        return null;
    }

    private String userToken(String context, ApplicationState s) {
        for (ExampleConfig.ApiApplication a : config.apiApplications)
            if (context.equals(a.contextId)) return a.userToken;
        for (ExampleConfig.Template t : config.templates)
            if (s.platformId.equals(t.platformId)
                    && s.templateId != null
                    && s.templateId.equals(t.templateId)) return t.userToken;
        return null;
    }

    private ObjectNode publicState(String id, ApplicationState s) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put(
                        "notificationUrl",
                        config.publicBaseUrl.replaceAll("/+$", "")
                                + "/jetlinks/events"
                                + (s.templateId == null ? "/" + ApiRequest.segment(id) : ""))
                .put("contextId", id)
                .put("type", s.templateId == null ? "api" : "business")
                .put("platformId", s.platformId)
                .put("applicationId", s.applicationId)
                .put("apiBaseUrl", s.apiBaseUrl)
                .put("keyRevision", s.keyRevision)
                .put("enabled", s.enabled)
                .put("deleted", s.deletedAt > 0)
                .put("credentialReady", s.accessSecret != null);
        return n;
    }

    private Mono<ExampleResponse> staticFile(String path) {
        return Mono.fromCallable(
                        () -> {
                            Path root = Paths.get(config.uiDir).toAbsolutePath().normalize(),
                                    file =
                                            root.resolve(
                                                            path.equals("/")
                                                                    ? "index.html"
                                                                    : path.substring(1))
                                                    .normalize();
                            if (!file.startsWith(root) || !Files.isRegularFile(file))
                                return ExampleResponse.error(404, "Build examples/shared-ui first");
                            String type =
                                    file.toString().endsWith(".js")
                                            ? "text/javascript"
                                            : file.toString().endsWith(".css")
                                                    ? "text/css"
                                                    : file.toString().endsWith(".html")
                                                            ? "text/html"
                                                            : "application/octet-stream";
                            return new ExampleResponse(
                                    200,
                                    Collections.singletonMap("Content-Type", type),
                                    Flux.just(Files.readAllBytes(file)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized void addCommand(ObjectNode n) {
        while (commandRecords.size() >= 100) commandRecords.removeLast();
        commandRecords.addFirst(n);
    }

    private synchronized int commandRecordsSize() {
        return commandRecords.size();
    }

    private synchronized JsonNode commandRecords(ApplicationState state) {
        List<ObjectNode> records = new ArrayList<>();
        for (ObjectNode record : commandRecords)
            if (state.platformId.equals(record.path("platformId").asText())
                    && state.applicationId.equals(record.path("applicationId").asText()))
                records.add(record);
        return Json.tree(records);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw != null)
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                try {
                    q.put(
                            java.net.URLDecoder.decode(kv[0], "UTF-8"),
                            kv.length == 1 ? "" : java.net.URLDecoder.decode(kv[1], "UTF-8"));
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new SdkException(Code.INVALID_STATE, "Cannot encode UTF-8 value", e);
                }
            }
        return q;
    }

    private static String cookie(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet())
            if (e.getKey().equalsIgnoreCase("Cookie"))
                for (String value : e.getValue())
                    for (String part : value.split(";"))
                        if (part.trim().startsWith(name + "="))
                            return part.trim().substring(name.length() + 1);
        return "";
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private static Map<String, String> mapString(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static JsonNode redact(JsonNode original) {
        JsonNode n = original.deepCopy();
        if (n.isObject()) {
            ObjectNode obj = (ObjectNode) n;
            Iterator<String> names = obj.fieldNames();
            List<String> fields = new ArrayList<>();
            names.forEachRemaining(fields::add);
            for (String key : fields) {
                if (key.toLowerCase(Locale.ROOT)
                        .matches(
                                "(access_?secret|client_?secret|secret|access_?token|refresh_?token|token|authorization|private_?key|encryptedaccesssecret)"))
                    obj.put(key, "[redacted]");
                else obj.set(key, redact(obj.get(key)));
            }
            if (obj.has("rawBody")) {
                try {
                    obj.put(
                            "rawBody",
                            new String(
                                    Json.bytes(
                                            redact(
                                                    Json.parse(
                                                            obj.path("rawBody")
                                                                    .asText()
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))),
                                    StandardCharsets.UTF_8));
                } catch (SdkException e) {
                    obj.put("rawBody", "[untrusted body retained server-side]");
                }
            }
        } else if (n.isArray())
            for (int i = 0; i < n.size(); i++) ((ArrayNode) n).set(i, redact(n.get(i)));
        return n;
    }

    public void close() {
        if (worker != null) worker.dispose();
        clients.values().forEach(Handle::close);
        clients.clear();
    }

    private static final class Handle implements AutoCloseable {
        final String fingerprint;
        final JetLinksReactiveClient client;
        final OAuth2 oauth;
        final CachedTokenProvider provider;

        Handle(String f, JetLinksReactiveClient c, OAuth2 o, CachedTokenProvider p) {
            fingerprint = f;
            client = c;
            oauth = o;
            provider = p;
        }

        public void close() {
            client.close();
            if (oauth != null) oauth.close();
        }
    }

    private static final class PendingLogin {
        final String context;
        final ApplicationState state;
        final OAuth2.Authorization auth;
        final boolean platform;
        final long expires = System.currentTimeMillis() + 600000;

        PendingLogin(String c, ApplicationState s, OAuth2.Authorization a, boolean p) {
            context = c;
            state = s;
            auth = a;
            platform = p;
        }
    }

    private static final class Session {
        final String context;
        final AuthorizationContext contextData;
        final long expires;

        Session(String c, AuthorizationContext d, long e) {
            context = c;
            contextData = d;
            expires = e;
        }
    }
}
