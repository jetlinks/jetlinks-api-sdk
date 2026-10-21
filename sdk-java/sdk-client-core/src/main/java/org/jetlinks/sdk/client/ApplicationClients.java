package org.jetlinks.sdk.client;

import org.jetlinks.sdk.client.auth.CachedTokenProvider;
import org.jetlinks.sdk.client.auth.OAuth2;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.notification.ApplicationState;

import java.time.Duration;
import java.util.*;

/**
 * 根据业务提供的当前应用快照复用客户端；只缓存连接及有界准入水位，不主动查询平台。返回的连接由本管理器统一关闭。 每次业务操作前调用
 * get，禁用、删除或轮换后不得继续持有旧连接。invalidate 保留水位，避免旧快照重新创建连接。
 *
 * @since 2.0
 */
public final class ApplicationClients implements AutoCloseable {
    /** 机器认证方式或调用方显式提供的 Bearer 身份。 */
    public enum Mode {
        LOCAL,
        OAUTH2,
        BEARER
    }

    /** 同一可信身份的双客户端及认证缓存；调用方可读取缓存统计，不能单独关闭共享连接。 */
    public static final class Handle {
        public final JetLinksReactiveClient client;
        public final JetLinksClient sync;
        public final CachedTokenProvider provider;
        private final OAuth2 oauth;
        private final String namespace, fingerprint;

        private Handle(
                String namespace,
                String fingerprint,
                ApplicationState state,
                Mode mode,
                List<String> scopes,
                String bearer) {
            this.namespace = namespace;
            this.fingerprint = fingerprint;
            oauth =
                    mode == Mode.OAUTH2
                            ? new OAuth2(
                                    state.apiBaseUrl, state.accessKey, state.accessSecret, true)
                            : null;
            provider =
                    mode == Mode.OAUTH2
                            ? oauth.machineTokens(scopes)
                            : mode == Mode.LOCAL
                                    ? OAuth2.localTokens(
                                            state.accessKey,
                                            state.accessSecret,
                                            scopes,
                                            Duration.ofMinutes(2))
                                    : null;
            client =
                    new JetLinksReactiveClient(
                            state.apiBaseUrl,
                            provider == null
                                    ? TokenProvider.bearer(Objects.requireNonNull(bearer))
                                    : provider);
            sync = new JetLinksClient(client);
        }

        private void close() {
            if (provider != null) provider.invalidate();
            client.close();
            if (oauth != null) oauth.close();
        }
    }

    private final Map<List<Object>, Handle> entries = new HashMap<>();
    private final Map<List<Object>, ApplicationVersion> versions = new HashMap<>();
    private final int limit;
    private boolean closed;

    public ApplicationClients() {
        this(128);
    }

    public ApplicationClients(int limit) {
        if (limit < 1)
            throw new SdkException(SdkException.Code.INVALID_ARGUMENT, "Invalid client limit");
        this.limit = limit;
    }

    public Handle get(String namespace, ApplicationState state, Mode mode) {
        return get(namespace, state, mode, null, null);
    }

    public Handle get(String namespace, ApplicationState state) {
        return get(namespace, state, Mode.OAUTH2);
    }

    /** namespace 隔离本地 API 通道或用户身份；null Scope 与空 Scope 的权限含义不同。 */
    public synchronized Handle get(
            String namespace,
            ApplicationState state,
            Mode mode,
            List<String> scopes,
            String bearer) {
        if (closed)
            throw new SdkException(SdkException.Code.INVALID_STATE, "Client manager closed");
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(mode);
        state = state.copy();
        List<Object> identity = Arrays.asList(namespace, state.platformId, state.applicationId);
        ApplicationVersion version = new ApplicationVersion(state);
        ApplicationVersion previous = versions.get(identity);
        version.check(previous);
        if (previous == null && versions.size() >= limit)
            throw new SdkException(
                    SdkException.Code.LIMIT_EXCEEDED, "Application identity capacity reached");
        versions.put(identity, version);
        if (!state.active()
                || (previous != null && !previous.connection.equals(version.connection)))
            invalidateIdentity(identity);
        if (!state.active()) {
            throw new SdkException(
                    SdkException.Code.INVALID_ARGUMENT,
                    "Application is inactive or credentials are missing");
        }
        List<String> normalized = scopes == null ? null : new ArrayList<>(new TreeSet<>(scopes));
        List<Object> key =
                Arrays.asList(namespace, state.platformId, state.applicationId, mode, normalized);
        String fingerprint =
                Signatures.sha256(
                        Json.bytes(
                                Arrays.asList(
                                        state.createdAt,
                                        state.apiBaseUrl,
                                        state.accessKey,
                                        state.accessSecret,
                                        state.keyRevision,
                                        bearer)));
        Handle existing = entries.get(key);
        if (existing != null && existing.fingerprint.equals(fingerprint)) return existing;
        if (existing == null && entries.size() >= limit)
            throw new SdkException(SdkException.Code.LIMIT_EXCEEDED, "Client capacity reached");
        Handle replacement =
                new Handle(namespace, fingerprint, state.copy(), mode, normalized, bearer);
        entries.put(key, replacement);
        if (existing != null) existing.close();
        return replacement;
    }

    private void invalidateIdentity(List<Object> identity) {
        entries.entrySet()
                .removeIf(
                        entry -> {
                            if (!entry.getKey().subList(0, 3).equals(identity)) return false;
                            entry.getValue().close();
                            return true;
                        });
    }

    /** 通知事务提交后失效本地通道的全部认证模式；保留版本水位，不影响其他 namespace。 */
    public synchronized void invalidate(String namespace) {
        entries.values()
                .removeIf(
                        handle -> {
                            if (!handle.namespace.equals(namespace)) return false;
                            handle.close();
                            return true;
                        });
    }

    public synchronized void close() {
        closed = true;
        entries.values().forEach(Handle::close);
        entries.clear();
        versions.clear();
    }
}
