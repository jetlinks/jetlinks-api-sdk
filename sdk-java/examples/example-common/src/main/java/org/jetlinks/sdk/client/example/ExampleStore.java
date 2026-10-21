package org.jetlinks.sdk.client.example;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.command.*;
import org.jetlinks.sdk.client.notification.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 示例专用的 H2 持久化 inbox 和应用状态；JDBC 在专用线程执行，同一实例串行执行受理事务。
 *
 * @since 2.0
 */
public final class ExampleStore implements NotificationStore {
    private final String url;
    private final Function<String, String> privateKeys;
    private final LongSupplier clock;

    public ExampleStore(String database, Function<String, String> privateKeys) {
        this(database, privateKeys, System::currentTimeMillis);
    }

    ExampleStore(String database, Function<String, String> privateKeys, LongSupplier clock) {
        url =
                "jdbc:h2:"
                        + java.nio.file.Paths.get(database).toAbsolutePath().normalize()
                        + ";DB_CLOSE_ON_EXIT=FALSE";
        this.privateKeys = privateKeys;
        this.clock = clock;
        try (Connection c = connection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS apps (id VARCHAR(1024) PRIMARY KEY, body CLOB NOT"
                            + " NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS inbox (id VARCHAR(2048) PRIMARY KEY)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS tenant_quotas (id VARCHAR(2048) PRIMARY KEY, body"
                            + " CLOB NOT NULL)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS receipts (id VARCHAR(64) PRIMARY KEY, received"
                            + " BIGINT NOT NULL, body CLOB NOT NULL)");
        } catch (SQLException e) {
            throw new SdkException(Code.INVALID_STATE, "Cannot open Example database", e);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /** 实际检查示例拥有的存储；公开结果不暴露 JDBC 地址或异常正文。 */
    public Mono<ServiceHealth> health() {
        return Mono.fromCallable(
                        () -> {
                            try (Connection c = connection();
                                    Statement s = c.createStatement()) {
                                s.executeQuery("SELECT 1").close();
                                return new ServiceHealth(ServiceHealth.Status.UP, null);
                            } catch (SQLException error) {
                                return new ServiceHealth(
                                        ServiceHealth.Status.DOWN, "Example storage unavailable");
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 示例单进程内与使用点串行；生产多副本须以数据库事务/条件更新保证同一原子边界。 */
    public Mono<SaveTenantLimits.Result> saveLimits(
            ApplicationState application, SaveTenantLimits input) {
        return Mono.fromCallable(() -> saveLimitsNow(application, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized SaveTenantLimits.Result saveLimitsNow(
            ApplicationState application, SaveTenantLimits input) throws SQLException {
        if (input.revision == null
                || input.revision < 0
                || input.limits == null
                || input.capabilities == null) {
            throw new SdkException(
                    Code.INVALID_ARGUMENT,
                    "revision, complete limits and capabilities are required");
        }
        List<SaveTenantLimits.Limit> limits = new ArrayList<>();
        for (SaveTenantLimits.Limit limit : input.limits) {
            // 示例仅实现自己声明的一个周期指标；先校验整批，失败不保存任何状态。
            if (limit == null
                    || !ExampleService.REQUEST_METRIC.equals(limit.metricId)
                    || limit.limit == null
                    || !limits.isEmpty()
                    || limit.windowDurationMillis == null
                    || limit.windowDurationMillis <= 0) {
                throw new SdkException(
                        Code.INVALID_ARGUMENT, "Invalid example metric or fixed window");
            }
            SaveTenantLimits.Limit normalized = new SaveTenantLimits.Limit();
            normalized.metricId = limit.metricId;
            normalized.limit = limit.limit.signum() < 0 ? BigDecimal.ONE.negate() : limit.limit;
            normalized.windowDurationMillis = limit.windowDurationMillis;
            limits.add(normalized);
        }
        List<ResourceCapability> capabilities = new ArrayList<>();
        for (ResourceCapability capability : input.capabilities) {
            if (capability == null
                    || !ExampleService.RESOURCE_ID.equals(capability.resource)
                    || capability.capabilities == null
                    || !Set.of(ExampleService.USE_CAPABILITY).containsAll(capability.capabilities)
                    || !capabilities.isEmpty()) {
                throw new SdkException(
                        Code.INVALID_ARGUMENT, "Invalid example resource capability");
            }
            capabilities.add(
                    new ResourceCapability(
                            capability.resource, Set.copyOf(capability.capabilities)));
        }
        try (Connection c = connection()) {
            QuotaState state = quota(c, application);
            if (input.revision > state.revision) {
                state.revision = input.revision;
                state.limits = limits;
                state.capabilities = capabilities;
                // 仅替换额度配置，绝不清零用量或改变已记录的统计窗口。
                saveQuota(c, application, state);
            }
            return new SaveTenantLimits.Result(state.revision);
        }
    }

    public Flux<TenantUsage> usage(ApplicationState application, ReadTenantUsage input) {
        return Mono.fromCallable(() -> readUsage(application, input))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private synchronized List<TenantUsage> readUsage(
            ApplicationState application, ReadTenantUsage input) throws SQLException {
        try (Connection c = connection()) {
            QuotaState state = quota(c, application);
            if (state.limits.isEmpty()
                    || input.metricIds != null
                            && !input.metricIds.isEmpty()
                            && !input.metricIds.contains(ExampleService.REQUEST_METRIC)) {
                return Collections.emptyList();
            }
            return Collections.singletonList(snapshot(state, clock.getAsLong()));
        }
    }

    /** 示例使用点校验并增加用量；额度不足或已撤销时失败，不写入失败的用量。 */
    public Mono<TenantUsage> useResource(ApplicationState application, BigDecimal amount) {
        return Mono.fromCallable(() -> useResourceNow(application, amount))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized TenantUsage useResourceNow(ApplicationState application, BigDecimal amount)
            throws SQLException {
        if (amount == null || amount.signum() <= 0) {
            throw new SdkException(Code.INVALID_ARGUMENT, "Positive resource amount is required");
        }
        try (Connection c = connection()) {
            QuotaState state = quota(c, application);
            if (state.limits.isEmpty()) {
                throw new SdkException(Code.LIMIT_EXCEEDED, "Example quota is not granted");
            }
            if (state.capabilities.stream()
                    .noneMatch(
                            capability ->
                                    ExampleService.RESOURCE_ID.equals(capability.resource)
                                            && capability.capabilities.contains(
                                                    ExampleService.USE_CAPABILITY))) {
                throw new SdkException(
                        Code.INVALID_STATE, "Example resource capability is not granted");
            }
            long now = clock.getAsLong();
            TenantUsage snapshot = snapshot(state, now);
            BigDecimal next = snapshot.usage.add(amount);
            BigDecimal limit = state.limits.get(0).limit;
            if (limit.signum() >= 0 && next.compareTo(limit) > 0) {
                throw new SdkException(Code.LIMIT_EXCEEDED, "Example quota exceeded");
            }
            state.usage = next;
            state.windowStart = snapshot.nextResetTime - snapshot.windowDurationMillis;
            saveQuota(c, application, state);
            snapshot.usage = next;
            return snapshot;
        }
    }

    private TenantUsage snapshot(QuotaState state, long now) {
        long duration = state.limits.get(0).windowDurationMillis;
        long start = Math.floorDiv(now, duration) * duration;
        TenantUsage result = new TenantUsage();
        result.metricId = ExampleService.REQUEST_METRIC;
        result.usage = state.windowStart == start ? state.usage : BigDecimal.ZERO;
        result.timestamp = now;
        result.windowDurationMillis = duration;
        result.nextResetTime = Math.addExact(start, duration);
        return result;
    }

    private String quotaKey(ApplicationState application) {
        // 相同应用 ID 删除后重建时，不继承上一生命周期的额度版本和用量。
        return identity(application.platformId, application.applicationId)
                + ":"
                + application.createdAt;
    }

    private QuotaState quota(Connection c, ApplicationState application) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("SELECT body FROM tenant_quotas WHERE id=?")) {
            s.setString(1, quotaKey(application));
            try (ResultSet r = s.executeQuery()) {
                return r.next()
                        ? Json.read(
                                r.getString(1).getBytes(StandardCharsets.UTF_8),
                                TypeRef.of(QuotaState.class))
                        : new QuotaState();
            }
        }
    }

    private void saveQuota(Connection c, ApplicationState application, QuotaState state)
            throws SQLException {
        // 单行保存快照、版本和已有用量，避免成功版本对应部分额度。
        try (PreparedStatement s =
                c.prepareStatement("MERGE INTO tenant_quotas(id,body) KEY(id) VALUES(?,?)")) {
            s.setString(1, quotaKey(application));
            s.setString(2, new String(Json.bytes(state), StandardCharsets.UTF_8));
            s.executeUpdate();
        }
    }

    /** 示例私有持久化结构，不属于 SDK 线上契约。 */
    private static class QuotaState {
        public long revision = -1;
        public List<SaveTenantLimits.Limit> limits = new ArrayList<>();
        public List<ResourceCapability> capabilities = new ArrayList<>();
        public BigDecimal usage = BigDecimal.ZERO;
        public long windowStart = -1;
    }

    public static String identity(String platform, String app) {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Json.bytes(Arrays.asList(platform, app)));
    }

    public Mono<ApplicationState> find(String platform, String app) {
        return Mono.fromCallable(
                        () -> {
                            try (Connection c = connection()) {
                                return find(c, identity(platform, app));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ApplicationState find(Connection c, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT body FROM apps WHERE id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next()
                        ? Json.MAPPER.convertValue(
                                Json.parse(r.getString(1).getBytes(StandardCharsets.UTF_8)),
                                ApplicationState.class)
                        : null;
            }
        }
    }

    public Mono<List<ApplicationState>> applications() {
        return Mono.fromCallable(
                        () -> {
                            List<ApplicationState> list = new ArrayList<>();
                            try (Connection c = connection();
                                    Statement s = c.createStatement();
                                    ResultSet r =
                                            s.executeQuery("SELECT body FROM apps ORDER BY id")) {
                                while (r.next())
                                    list.add(
                                            Json.MAPPER.readValue(
                                                    r.getString(1), ApplicationState.class));
                            }
                            return list;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> accept(VerifiedNotification n) {
        return Mono.fromCallable(() -> persist(n)).subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized boolean persist(VerifiedNotification n) throws SQLException {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                String eventKey =
                        org.jetlinks.sdk.client.crypto.Signatures.sha256(
                                org.jetlinks.sdk.client.crypto.Signatures.lp(
                                        n.key.channelId, n.platformId, n.applicationId, n.eventId));
                boolean duplicate;
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM inbox WHERE id=?")) {
                    s.setString(1, eventKey);
                    try (ResultSet r = s.executeQuery()) {
                        duplicate = r.next();
                    }
                }
                if (!duplicate) {
                    if (TrustedKey.BUSINESS.equals(n.key.type)) {
                        ApplicationState current = find(c, identity(n.platformId, n.applicationId));
                        ApplicationState next = ApplicationLifecycle.apply(current, n, privateKeys);
                        try (PreparedStatement s =
                                c.prepareStatement(
                                        "MERGE INTO apps (id,body) KEY(id) VALUES (?,?)")) {
                            s.setString(1, identity(n.platformId, n.applicationId));
                            s.setString(2, new String(Json.bytes(next), StandardCharsets.UTF_8));
                            s.executeUpdate();
                        }
                    }
                    try (PreparedStatement s =
                            c.prepareStatement("INSERT INTO inbox(id) VALUES(?)")) {
                        s.setString(1, eventKey);
                        s.executeUpdate();
                    }
                }
                ObjectNode receipt = Json.MAPPER.createObjectNode();
                receipt.put("channelId", n.key.channelId)
                        .put("platformId", n.platformId)
                        .put("applicationId", n.applicationId)
                        .put("eventId", n.eventId)
                        .put("type", n.event.type)
                        .put("verified", true)
                        .put("accepted", true)
                        .put("duplicate", duplicate)
                        .put("rawBody", new String(n.rawBody, StandardCharsets.UTF_8));
                receipt.set("event", Json.tree(n.event));
                receipt.set("signatureHeaders", Json.tree(n.signatureHeaders));
                receipt.put(
                        "rawBodySha256",
                        org.jetlinks.sdk.client.crypto.Signatures.sha256(n.rawBody));
                insertReceipt(c, receipt);
                c.commit();
                return !duplicate;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                throw e;
            }
        }
    }

    public Mono<Void> rejected(byte[] raw) {
        return Mono.fromRunnable(
                        () -> {
                            try (Connection c = connection()) {
                                ObjectNode n = Json.MAPPER.createObjectNode();
                                n.put("verified", false)
                                        .put("accepted", false)
                                        .put("rawBody", new String(raw, StandardCharsets.UTF_8));
                                insertReceipt(c, n);
                            } catch (SQLException e) {
                                throw new SdkException(
                                        Code.INVALID_STATE, "Cannot retain rejection", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void insertReceipt(Connection c, ObjectNode n) throws SQLException {
        n.put("recordId", UUID.randomUUID().toString())
                .put("receivedAt", System.currentTimeMillis());
        try (PreparedStatement s =
                c.prepareStatement("INSERT INTO receipts(id,received,body) VALUES(?,?,?)")) {
            s.setString(1, n.path("recordId").asText());
            s.setLong(2, n.path("receivedAt").asLong());
            s.setString(3, new String(Json.bytes(n), StandardCharsets.UTF_8));
            s.executeUpdate();
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                    "DELETE FROM receipts WHERE id NOT IN (SELECT id FROM receipts ORDER BY"
                            + " received DESC LIMIT 1000)");
        }
    }

    public Mono<JsonNode> receipts(String id, int page, Map<String, String> filters) {
        return Mono.fromCallable(
                        () -> {
                            ArrayNode data = Json.MAPPER.createArrayNode();
                            try (Connection c = connection();
                                    Statement s = c.createStatement();
                                    ResultSet r =
                                            s.executeQuery(
                                                    "SELECT body FROM receipts ORDER BY received"
                                                            + " DESC")) {
                                while (r.next()) {
                                    JsonNode n =
                                            Json.parse(
                                                    r.getString(1)
                                                            .getBytes(StandardCharsets.UTF_8));
                                    if (id != null) {
                                        if (id.equals(n.path("recordId").asText())) return n;
                                    } else {
                                        boolean matches = true;
                                        for (String field :
                                                Arrays.asList(
                                                        "applicationId",
                                                        "platformId",
                                                        "channelId",
                                                        "type",
                                                        "eventId"))
                                            if (filters.containsKey(field)
                                                    && !filters.get(field)
                                                            .equals(n.path(field).asText()))
                                                matches = false;
                                        String status =
                                                n.path("accepted").asBoolean()
                                                        ? n.path("duplicate").asBoolean()
                                                                ? "duplicate"
                                                                : "accepted"
                                                        : "rejected";
                                        if (filters.containsKey("status")
                                                && !filters.get("status").equals(status))
                                            matches = false;
                                        if (matches) data.add(n);
                                    }
                                }
                            }
                            if (id != null)
                                throw new SdkException(
                                        Code.INVALID_ARGUMENT, "Unknown notification");
                            ObjectNode result = Json.MAPPER.createObjectNode();
                            result.put("total", data.size());
                            ArrayNode slice = result.putArray("data");
                            for (int i = Math.max(0, page) * 50;
                                    i < Math.min(data.size(), Math.max(0, page) * 50 + 50);
                                    i++) slice.add(data.get(i));
                            return (JsonNode) result;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
