package org.jetlinks.sdk.client.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jetlinks.sdk.client.Json;
import org.jetlinks.sdk.client.SdkException;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.TypeRef;
import org.jetlinks.sdk.client.crypto.ProtocolHeaders;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.notification.ApplicationState;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 独立于 Web 框架的签名命令服务。每次请求使用凭据快照，正文按需流式输出；适配器必须关闭未消费的响应。
 *
 * @since 2.0
 */
public final class CommandService {
    private static final String SIGNATURE_DOMAIN = "http-command";
    private static final String REQUEST_DIRECTION = "request";
    private static final String RESPONSE_DIRECTION = "response";
    private static final String COMMAND_PATH = "/commands/" + CommandCapabilities.VERSION_1 + "/";
    private static final Logger LOGGER = Logger.getLogger(CommandService.class.getName());

    /**
     * 命令失败的服务端诊断信息，包含验签后的关联标识和原始原因，不包含凭据、请求正文或查询参数。 不应序列化到 HTTP 响应；处理器异常中的敏感信息由应用自行脱敏。
     *
     * @since 2.0
     * @see CommandService#onError(Consumer)
     */
    public static final class Failure {
        public final String platformId, applicationId, requestId, commandId;
        public final Throwable cause;

        private Failure(Verified request, String commandId, Throwable cause) {
            platformId = request.state.platformId;
            applicationId = request.state.applicationId;
            requestId = request.requestId;
            this.commandId = commandId;
            this.cause = cause;
        }
    }

    /**
     * 异步查找当前有效的应用实例。返回空值拒绝请求，失败直接传播且不执行命令。
     *
     * @since 2.0
     */
    public interface Credentials {
        /**
         * 按已提供的区域和应用 ID 查询凭据快照；SDK 随后检查启用状态、归属和签名。
         *
         * @return 有效实例，找不到时返回空 Mono
         * @since 2.0
         */
        Mono<ApplicationState> resolve(String platform, String application);
    }

    /**
     * 验签后调用的业务处理器。实现必须非阻塞，业务副作用由应用自行保证幂等。
     *
     * @since 2.0
     */
    public interface Handler<T> {
        /**
         * 执行单结果或流式命令；参数已解码，保留身份字段已由 SDK 覆盖。
         *
         * @param application 本次请求的可信应用快照
         * @param parameters 注册时指定类型的参数
         * @return 按需输出的业务结果；取消信号应传递给底层任务
         * @since 2.0
         */
        Publisher<?> execute(ApplicationState application, T parameters);
    }

    private static final class Definition {
        final boolean stream;
        final Handler<JsonNode> handler;
        final Predicate<ApplicationState> allowed;
        volatile Map<String, Object> metadata;

        Definition(boolean stream, Handler<JsonNode> handler, Predicate<ApplicationState> allowed) {
            this.stream = stream;
            this.handler = handler;
            this.allowed = allowed;
        }
    }

    private static final class Verified {
        final ApplicationState state;
        final String requestId, signature, method, target;

        Verified(
                ApplicationState state, String id, String signature, String method, String target) {
            this.state = state.copy();
            requestId = id;
            this.signature = signature;
            this.method = method;
            this.target = target;
        }
    }

    private final Credentials credentials;
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> budgets = new LinkedHashMap<>(16, .75f, true);
    private final int maxConcurrency;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Consumer<Failure> errorHandler = CommandService::logFailure;

    public CommandService(Credentials credentials, int maxConcurrency) {
        if (maxConcurrency < 1)
            throw new SdkException(Code.INVALID_ARGUMENT, "Invalid concurrency");
        this.credentials = credentials;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * 观察验签后的命令失败，默认写入本类的 JDK Logger。观察器在请求或输出线程调用，必须非阻塞且支持并发。 观察器失败不替换原始异常，也不阻止并发名额释放；签名拒绝、取消及正常的
     * 404/429/503 不通知此观察器。
     *
     * @param handler 非空服务端观察器，不应将诊断信息返回给客户端
     * @return 当前服务，应在接收请求前完成配置
     * @since 2.0
     * @see Failure
     */
    public CommandService onError(Consumer<Failure> handler) {
        errorHandler = Objects.requireNonNull(handler);
        return this;
    }

    private static void logFailure(Failure failure) {
        LOGGER.log(
                Level.WARNING,
                "Command failed: platformId="
                        + failure.platformId
                        + ", applicationId="
                        + failure.applicationId
                        + ", requestId="
                        + failure.requestId
                        + ", commandId="
                        + failure.commandId,
                failure.cause);
    }

    private void reportFailure(Verified request, String commandId, Throwable cause) {
        Failure failure = new Failure(request, commandId, cause);
        try {
            errorHandler.accept(failure);
        } catch (RuntimeException observerError) {
            // 诊断失败不能替换协议错误，也不能阻止并发名额释放。
            logFailure(failure);
            LOGGER.log(Level.WARNING, "Command error observer failed", observerError);
        }
    }

    public CommandService register(
            String id, boolean stream, Handler<Map<String, Object>> handler) {
        return register(id, stream, handler, a -> true);
    }

    public CommandService register(
            String id,
            boolean stream,
            Handler<Map<String, Object>> handler,
            Predicate<ApplicationState> allowed) {
        return register(id, stream, new TypeRef<Map<String, Object>>() {}, handler, allowed);
    }

    public <T> CommandService register(
            String id, boolean stream, Class<T> type, Handler<T> handler) {
        return register(id, stream, TypeRef.of(type), handler, a -> true);
    }

    public <T> CommandService register(
            String id, boolean stream, TypeRef<T> type, Handler<T> handler) {
        return register(id, stream, type, handler, a -> true);
    }

    public <T> CommandService register(
            String id,
            boolean stream,
            Class<T> type,
            Handler<T> handler,
            Predicate<ApplicationState> allowed) {
        return register(id, stream, TypeRef.of(type), handler, allowed);
    }

    /** 仅在验签并注入可信应用身份后，才将命令参数转换成指定类型。 */
    public <T> CommandService register(
            String id,
            boolean stream,
            TypeRef<T> type,
            Handler<T> handler,
            Predicate<ApplicationState> allowed) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(allowed);
        Handler<JsonNode> decoder =
                (application, input) -> handler.execute(application, Json.convert(input, type));
        if (id == null
                || id.isEmpty()
                || definitions.size() >= 4096
                || definitions.putIfAbsent(id, new Definition(stream, decoder, allowed)) != null)
            throw new SdkException(Code.INVALID_ARGUMENT, "Duplicate command or registry full");
        return this;
    }

    /** 可选的本地参数元数据，不改变线上能力列表；命令是否支持由注册情况决定。 */
    public CommandService metadata(String id, Map<String, Object> metadata) {
        Definition d = definitions.get(id);
        if (d == null) throw new SdkException(Code.INVALID_ARGUMENT, "Unknown command");
        d.metadata =
                metadata == null
                        ? null
                        : Json.convert(metadata, new TypeRef<Map<String, Object>>() {});
        return this;
    }

    public Optional<Map<String, Object>> metadata(String id) {
        Definition d = definitions.get(id);
        return d == null || d.metadata == null
                ? Optional.empty()
                : Optional.of(Json.convert(d.metadata, new TypeRef<Map<String, Object>>() {}));
    }

    /** 仅列出当前应用允许执行的已注册命令；列表按 ID 排序。 */
    public List<String> capabilities(ApplicationState application) {
        List<String> ids = new ArrayList<>();
        definitions.forEach(
                (id, d) -> {
                    if (d.allowed.test(application)) ids.add(id);
                });
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    /** 当前占用并发名额的请求数，供服务端监控使用。 */
    public int inFlight() {
        return inFlight.get();
    }

    /** 验签后按应用分配并发预算，再执行命令；未消费响应必须显式关闭。 */
    public Mono<Response> handle(
            String method, String target, Map<String, List<String>> headers, byte[] body) {
        return Mono.defer(() -> verify(method, target, headers))
                .flatMap(v -> execute(v, body))
                .doOnDiscard(Response.class, Response::close);
    }

    private Mono<Verified> verify(String method, String target, Map<String, List<String>> headers) {
        Map<String, List<String>> trimmed = new LinkedHashMap<>();
        headers.forEach(
                (name, values) -> {
                    List<String> copy = new ArrayList<>();
                    values.forEach(value -> copy.add(value.trim()));
                    trimmed.put(name, copy);
                });
        ProtocolHeaders h = new ProtocolHeaders(trimmed);
        h.version();
        h.timestamp(System.currentTimeMillis(), 300000);
        String platform = h.required(ProtocolHeaders.PLATFORM_ID),
                app = h.required(ProtocolHeaders.APPLICATION_ID);
        String key = h.required(ProtocolHeaders.KEY_ID),
                id = h.required(ProtocolHeaders.REQUEST_ID),
                signature = h.required(ProtocolHeaders.SIGNATURE);
        if (!platform.matches("[!-~]{1,128}") || !id.matches("[a-f0-9]{32}"))
            throw new SdkException(Code.AUTHENTICATION, "Invalid command identity");
        return credentials
                .resolve(platform, app)
                .switchIfEmpty(
                        Mono.error(
                                () ->
                                        new SdkException(
                                                Code.AUTHENTICATION,
                                                "Unknown command application")))
                .map(
                        state -> {
                            if (!state.active()
                                    || !platform.equals(state.platformId)
                                    || !app.equals(state.applicationId)
                                    || !key.equals(state.accessKey))
                                throw new SdkException(
                                        Code.AUTHENTICATION,
                                        "Inactive command credential or ownership mismatch");
                            String expected =
                                    Signatures.sign(
                                            state.accessSecret,
                                            SIGNATURE_DOMAIN,
                                            ProtocolHeaders.VERSION_1,
                                            REQUEST_DIRECTION,
                                            app,
                                            platform,
                                            key,
                                            h.required(ProtocolHeaders.TIMESTAMP),
                                            id,
                                            method,
                                            target,
                                            h.optional("Content-Type"),
                                            h.optional("Accept"));
                            if (!Signatures.matches(expected, signature))
                                throw new SdkException(
                                        Code.AUTHENTICATION, "Command signature rejected");
                            return new Verified(state, id, signature, method, target);
                        });
    }

    private synchronized Semaphore budgetFor(ApplicationState state) {
        String id = Signatures.sha256(Signatures.lp(state.platformId, state.applicationId));
        Semaphore existing = budgets.get(id);
        if (existing != null) return existing;
        // 只淘汰空闲身份；凭据轮换期间，在途请求继续使用同一并发预算。
        if (budgets.size() >= 1024) {
            Iterator<Map.Entry<String, Semaphore>> entries = budgets.entrySet().iterator();
            while (entries.hasNext())
                if (entries.next().getValue().availablePermits() == maxConcurrency) {
                    entries.remove();
                    break;
                }
        }
        if (budgets.size() >= 1024) return null;
        Semaphore created = new Semaphore(maxConcurrency);
        budgets.put(id, created);
        return created;
    }

    private Mono<Response> execute(Verified v, byte[] body) {
        Semaphore budget;
        // 预算查找与获取名额共用淘汰锁，防止同一应用在两步之间被拆成多个预算。
        synchronized (this) {
            budget = budgetFor(v.state);
            if (budget == null) return Mono.just(error(v, 503, () -> {}));
            if (!budget.tryAcquire()) return Mono.just(error(v, 429, () -> {}));
        }
        AtomicBoolean released = new AtomicBoolean();
        inFlight.incrementAndGet();
        Runnable release =
                () -> {
                    if (released.compareAndSet(false, true)) {
                        inFlight.decrementAndGet();
                        budget.release();
                    }
                };
        String commandId = "";
        try {
            String path = v.target.split("\\?", 2)[0];
            if ("GET".equals(v.method) && path.endsWith("/capabilities")) {
                CommandCapabilities capability = new CommandCapabilities(capabilities(v.state));
                return Mono.just(
                        response(
                                v,
                                200,
                                false,
                                Flux.just(Json.tree(Collections.singletonList(capability))),
                                release));
            }
            int pos = path.indexOf(COMMAND_PATH);
            if (!"POST".equals(v.method) || pos < 0) return Mono.just(error(v, 404, release));
            commandId = URLDecoder.decode(path.substring(pos + COMMAND_PATH.length()), "UTF-8");
            Definition d = definitions.get(commandId);
            if (d == null || !d.allowed.test(v.state)) return Mono.just(error(v, 404, release));
            return executeCommand(v, commandId, d, body, release);
        } catch (Exception e) {
            reportFailure(v, commandId, e);
            return Mono.just(error(v, 400, release));
        }
    }

    private Mono<Response> executeCommand(
            Verified request,
            String commandId,
            Definition definition,
            byte[] body,
            Runnable release) {
        JsonNode parameters = body.length == 0 ? Json.MAPPER.createObjectNode() : Json.parse(body);
        if (parameters.isObject())
            ((ObjectNode) parameters)
                    .put("applicationId", request.state.applicationId)
                    .put("templateId", request.state.templateId);
        Flux<JsonNode> values =
                Flux.defer(
                                () ->
                                        Flux.from(
                                                definition.handler.execute(
                                                        request.state.copy(), parameters)))
                        .map(Json::tree)
                        .timeout(Duration.ofSeconds(30));
        if (definition.stream)
            return Mono.just(response(request, 200, true, commandId, values, release));
        return values.singleOrEmpty()
                .defaultIfEmpty(Json.MAPPER.nullNode())
                .map(value -> response(request, 200, false, commandId, Flux.just(value), release))
                .onErrorResume(
                        error -> {
                            reportFailure(request, commandId, error);
                            return Mono.just(error(request, 500, release));
                        })
                .doOnCancel(release);
    }

    private Response error(Verified v, int status, Runnable release) {
        return response(
                v,
                status,
                false,
                Flux.just(Json.tree(Collections.singletonMap("error", "Command failed"))),
                release);
    }

    private Response response(
            Verified v, int status, boolean stream, Flux<JsonNode> values, Runnable release) {
        return response(v, status, stream, "", values, release);
    }

    private Response response(
            Verified v,
            int status,
            boolean stream,
            String commandId,
            Flux<JsonNode> values,
            Runnable release) {
        ApplicationState state = v.state;
        String time = Long.toString(System.currentTimeMillis()),
                type = stream ? "application/x-ndjson" : "application/json";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ProtocolHeaders.SIGNATURE_VERSION, ProtocolHeaders.VERSION_1);
        headers.put(ProtocolHeaders.PLATFORM_ID, state.platformId);
        headers.put(ProtocolHeaders.APPLICATION_ID, state.applicationId);
        headers.put(ProtocolHeaders.KEY_ID, state.accessKey);
        headers.put(ProtocolHeaders.TIMESTAMP, time);
        headers.put(ProtocolHeaders.REQUEST_ID, v.requestId);
        headers.put("Content-Type", type);
        headers.put(
                ProtocolHeaders.SIGNATURE,
                Signatures.sign(
                        state.accessSecret,
                        SIGNATURE_DOMAIN,
                        ProtocolHeaders.VERSION_1,
                        RESPONSE_DIRECTION,
                        state.applicationId,
                        state.platformId,
                        state.accessKey,
                        time,
                        v.requestId,
                        v.signature,
                        Integer.toString(status),
                        type));
        Flux<byte[]> body =
                values.map(
                                value -> {
                                    byte[] bytes = Json.bytes(value);
                                    if (bytes.length > 1024 * 1024)
                                        throw new SdkException(
                                                Code.LIMIT_EXCEEDED,
                                                "Command result exceeds limit");
                                    if (!stream) return bytes;
                                    byte[] line = Arrays.copyOf(bytes, bytes.length + 1);
                                    line[bytes.length] = 10;
                                    return line;
                                })
                        // 流式或序列化失败可能发生在响应头签名之后；保留原始异常并终止输出，不重写响应。
                        .doOnError(error -> reportFailure(v, commandId, error))
                        .doFinally(s -> release.run());
        return new Response(status, headers, body, release);
    }

    /**
     * 正文只订阅一次。响应未交给 HTTP 写入器或被中途放弃时也必须关闭，以释放并发名额。
     *
     * @since 2.0
     */
    public static final class Response implements AutoCloseable {
        public final int status;
        public final Map<String, String> headers;
        public final Flux<byte[]> body;
        private final Runnable release;

        Response(int status, Map<String, String> headers, Flux<byte[]> body, Runnable release) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.release = release;
        }

        public void close() {
            release.run();
        }
    }
}
