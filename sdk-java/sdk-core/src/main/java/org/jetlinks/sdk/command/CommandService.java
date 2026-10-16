package org.jetlinks.sdk.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jetlinks.sdk.Json;
import org.jetlinks.sdk.TypeRef;
import org.jetlinks.sdk.crypto.ProtocolHeaders;
import org.jetlinks.sdk.crypto.Signatures;
import org.jetlinks.sdk.notification.ApplicationState;
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
 * Framework-independent signed command server. Credentials are request snapshots. Bodies remain
 * streaming; adapters must close abandoned responses.
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
     * Server-side command failure with verified correlation identifiers and the original cause.
     * Contains no credentials, request body or query string; never include it in the HTTP response.
     * The application owns any retention and redaction of the cause supplied by its handler.
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
     * Resolve an active instance asynchronously. Empty rejects the request; errors propagate
     * without executing it.
     */
    public interface Credentials {
        Mono<ApplicationState> resolve(String platform, String application);
    }

    /**
     * Called after verification. Must remain nonblocking; business side effects require their own
     * idempotency.
     */
    public interface Handler<T> {
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
        if (maxConcurrency < 1) throw new IllegalArgumentException("Invalid concurrency");
        this.credentials = credentials;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * Observes failures after request verification, before conversion to a signed error or stream
     * termination. The default writes to the JDK logger named after this class. The callback runs
     * on the request/output thread and must be nonblocking; it may be called concurrently. Observer
     * exceptions are logged without replacing the command failure or preventing budget release.
     * Signature rejections, cancellation and normal 404/429/503 responses are not reported here.
     *
     * @param handler non-null server-side observer; do not serialize the failure to an HTTP client
     * @return this service for configuration before serving requests
     * @since 2.0
     * @see Failure
     */
    public CommandService onError(Consumer<Failure> handler) {
        errorHandler = Objects.requireNonNull(handler);
        return this;
    }

    private static void logFailure(Failure failure) {
        LOGGER.log(Level.WARNING,
                "Command failed: platformId=" + failure.platformId
                        + ", applicationId=" + failure.applicationId
                        + ", requestId=" + failure.requestId
                        + ", commandId=" + failure.commandId,
                failure.cause);
    }

    private void reportFailure(Verified request, String commandId, Throwable cause) {
        Failure failure = new Failure(request, commandId, cause);
        try {
            errorHandler.accept(failure);
        } catch (RuntimeException observerError) {
            // Diagnostics cannot replace a signed error or leak its concurrency permit.
            logFailure(failure);
            LOGGER.log(Level.WARNING, "Command error observer failed", observerError);
        }
    }

    public CommandService register(String id, boolean stream, Handler<Map<String, Object>> handler) {
        return register(id, stream, handler, a -> true);
    }

    public CommandService register(
            String id, boolean stream, Handler<Map<String, Object>> handler,
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
            String id, boolean stream, Class<T> type, Handler<T> handler,
            Predicate<ApplicationState> allowed) {
        return register(id, stream, TypeRef.of(type), handler, allowed);
    }

    /** Parameters are decoded only after signature verification and trusted identity injection. */
    public <T> CommandService register(
            String id, boolean stream, TypeRef<T> type, Handler<T> handler,
            Predicate<ApplicationState> allowed) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(allowed);
        Handler<JsonNode> decoder = (application, input) ->
                handler.execute(application, Json.convert(input, type));
        if (id == null
                || id.isEmpty()
                || definitions.size() >= 4096
                || definitions.putIfAbsent(id, new Definition(stream, decoder, allowed)) != null)
            throw new IllegalArgumentException("Duplicate command or registry full");
        return this;
    }

    /**
     * Optional local schema/metadata; it does not alter the wire capability list or define support.
     */
    public CommandService metadata(String id, Map<String, Object> metadata) {
        Definition d = definitions.get(id);
        if (d == null) throw new IllegalArgumentException("Unknown command");
        d.metadata = metadata == null ? null : Json.convert(metadata, new TypeRef<Map<String, Object>>() {});
        return this;
    }

    public Optional<Map<String, Object>> metadata(String id) {
        Definition d = definitions.get(id);
        return d == null || d.metadata == null
                ? Optional.empty()
                : Optional.of(Json.convert(d.metadata, new TypeRef<Map<String, Object>>() {}));
    }

    public List<String> capabilities(ApplicationState application) {
        List<String> ids = new ArrayList<>();
        definitions.forEach(
                (id, d) -> {
                    if (d.allowed.test(application)) ids.add(id);
                });
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    public int inFlight() {
        return inFlight.get();
    }

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
        String platform = h.required(ProtocolHeaders.PLATFORM_ID), app = h.required(ProtocolHeaders.APPLICATION_ID);
        String key = h.required(ProtocolHeaders.KEY_ID),
                id = h.required(ProtocolHeaders.REQUEST_ID),
                signature = h.required(ProtocolHeaders.SIGNATURE);
        if (!platform.matches("[!-~]{1,128}") || !id.matches("[a-f0-9]{32}"))
            throw new SecurityException("Invalid command identity");
        return credentials
                .resolve(platform, app)
                .switchIfEmpty(Mono.error(new SecurityException("Unknown command application")))
                .map(
                        state -> {
                            if (!state.active()
                                    || !platform.equals(state.platformId)
                                    || !app.equals(state.applicationId)
                                    || !key.equals(state.accessKey))
                                throw new SecurityException(
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
                                throw new SecurityException("Command signature rejected");
                            return new Verified(state, id, signature, method, target);
                        });
    }

    private synchronized Semaphore budgetFor(ApplicationState state) {
        String id = Signatures.sha256(Signatures.lp(state.platformId, state.applicationId));
        Semaphore existing = budgets.get(id);
        if (existing != null) return existing;
        // Evict only idle identities. In-flight requests keep their budget across credential
        // rotation.
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
        // Selection and acquisition share the eviction lock so an idle budget cannot be
        // replaced between lookup and acquisition, splitting one application's limit.
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
            Verified request, String commandId, Definition definition, byte[] body, Runnable release) {
        JsonNode parameters = body.length == 0 ? Json.MAPPER.createObjectNode() : Json.parse(body);
        if (parameters.isObject())
            ((ObjectNode) parameters)
                    .put("applicationId", request.state.applicationId)
                    .put("templateId", request.state.templateId);
        Flux<JsonNode> values = Flux.defer(
                        () -> Flux.from(definition.handler.execute(request.state.copy(), parameters)))
                .map(Json::tree)
                .timeout(Duration.ofSeconds(30));
        if (definition.stream)
            return Mono.just(response(request, 200, true, commandId, values, release));
        return values.singleOrEmpty()
                .defaultIfEmpty(Json.MAPPER.nullNode())
                .map(value -> response(request, 200, false, commandId, Flux.just(value), release))
                .onErrorResume(error -> {
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
            Verified v, int status, boolean stream, String commandId,
            Flux<JsonNode> values, Runnable release) {
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
                                        throw new IllegalArgumentException(
                                                "Command result exceeds limit");
                                    if (!stream) return bytes;
                                    byte[] line = Arrays.copyOf(bytes, bytes.length + 1);
                                    line[bytes.length] = 10;
                                    return line;
                                })
                        // Streaming/serialization failures occur after headers have been signed;
                        // observe the original failure and terminate without rewriting that response.
                        .doOnError(error -> reportFailure(v, commandId, error))
                        .doFinally(s -> release.run());
        return new Response(status, headers, body, release);
    }

    /**
     * Subscribe to body once. Close abandoned responses, including failures before the HTTP writer
     * subscribes.
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
