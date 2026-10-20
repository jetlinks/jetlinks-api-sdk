package org.jetlinks.sdk.client;

import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.notification.ApplicationState;

import java.util.Arrays;

/** 有界缓存中的准入水位与摘要，不保存第二份应用状态或凭据明文。 */
final class ApplicationVersion {
    final long createdAt, updatedAt, keyRevision;
    final boolean deleted;
    final String status, credentials, connection;

    ApplicationVersion(ApplicationState state) {
        if (state.createdAt < 0
                || state.updatedAt < 0
                || state.keyRevision < 0
                || state.deletedAt < 0)
            throw new SdkException(
                    SdkException.Code.INVALID_ARGUMENT, "Invalid application version");
        createdAt = state.createdAt;
        updatedAt = state.updatedAt;
        keyRevision = state.keyRevision;
        deleted = state.deletedAt != 0;
        status = digest(state.enabled, state.deletedAt, state.apiBaseUrl);
        credentials = digest(state.accessKey, state.accessSecret);
        connection = digest(createdAt, state.apiBaseUrl, credentials, keyRevision);
    }

    private static String digest(Object... fields) {
        return Signatures.sha256(Json.bytes(Arrays.asList(fields)));
    }

    void check(ApplicationVersion previous) {
        // 状态时间和凭据版本独立推进；删除是同生命周期的终态，同时间删除优先。
        if (previous == null || createdAt > previous.createdAt) return;
        if (createdAt < previous.createdAt
                || updatedAt < previous.updatedAt
                || keyRevision < previous.keyRevision
                || (previous.deleted && !deleted)
                || (updatedAt == previous.updatedAt
                        && !status.equals(previous.status)
                        && !(deleted && !previous.deleted))
                || (keyRevision == previous.keyRevision
                        && !deleted
                        && !credentials.equals(previous.credentials)))
            throw new SdkException(
                    SdkException.Code.INVALID_STATE, "Stale or conflicting application snapshot");
    }
}
