package org.jetlinks.sdk.example;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.notification.*;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Example-only H2 durable inbox and lifecycle store. All JDBC runs off event loops; one instance
 * serializes acceptance transactions. @since 2.0
 */
public final class ExampleStore implements NotificationStore {
    private final String url;
    private final Function<String, String> privateKeys;

    public ExampleStore(String database, Function<String, String> privateKeys) {
        url =
                "jdbc:h2:"
                        + java.nio.file.Paths.get(database).toAbsolutePath().normalize()
                        + ";DB_CLOSE_ON_EXIT=FALSE";
        this.privateKeys = privateKeys;
        try (Connection c = connection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS apps (id VARCHAR(1024) PRIMARY KEY, body CLOB NOT"
                            + " NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS inbox (id VARCHAR(2048) PRIMARY KEY)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS receipts (id VARCHAR(64) PRIMARY KEY, received"
                            + " BIGINT NOT NULL, body CLOB NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open Example database", e);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
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
                        org.jetlinks.sdk.crypto.Signatures.sha256(
                                org.jetlinks.sdk.crypto.Signatures.lp(
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
                receipt.put("rawBodySha256", org.jetlinks.sdk.crypto.Signatures.sha256(n.rawBody));
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
                                throw new IllegalStateException("Cannot retain rejection", e);
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
                                throw new IllegalArgumentException("Unknown notification");
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
