package org.jetlinks.sdk.client.notification;

import static org.jetlinks.sdk.client.event.EventTypes.*;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.crypto.Signatures;
import org.jetlinks.sdk.client.event.*;

import java.net.URI;
import java.util.function.Function;

/**
 * 应用生命周期的纯状态合并；调用方需将结果与 inbox 在同一事务中保存。此处不操作缓存或主动轮换密钥。
 *
 * @since 2.0
 */
public final class ApplicationLifecycle {
    private ApplicationLifecycle() {}

    /** 合并已验签的生命周期事件，忽略迟到状态和旧凭据版本；不修改传入实例。 */
    public static ApplicationState apply(
            ApplicationState current,
            VerifiedNotification notice,
            Function<String, String> privateKeys) {
        ApplicationState state = current == null ? new ApplicationState() : current.copy();
        if (current != null
                && (!notice.platformId.equals(current.platformId)
                        || !notice.applicationId.equals(current.applicationId)
                        || !notice.key.templateId.equals(current.templateId)))
            throw new SdkException(Code.AUTHENTICATION, "Existing ownership conflict");
        state.platformId = notice.platformId;
        state.applicationId = notice.applicationId;
        state.templateId = notice.key.templateId;
        String type = notice.event.type == null ? "" : notice.event.type;
        if (!EventTypes.isApplicationEvent(type)) return state;
        long time = notice.event.occurredAt == null ? -1 : notice.event.occurredAt;
        if (time < 0) throw new SdkException(Code.INVALID_ARGUMENT, "Missing lifecycle occurredAt");
        if (time < state.createdAt) return state;
        boolean created = APPLICATION_CREATED.equals(type);
        if (APPLICATION_DELETED.equals(type)) {
            if (time >= state.updatedAt) {
                state.deletedAt = time;
                state.updatedAt = time;
                state.enabled = false;
                state.accessSecret = null;
                state.accessKey = null;
            }
            return state;
        }
        // 同步和更新不能恢复已删除实例；后续创建开启新生命周期，凭据版本仅在该生命周期内比较。
        if (state.deletedAt > 0 && (!created || time <= state.deletedAt)) return state;
        if (created && time > state.createdAt && (state.createdAt > 0 || time > state.updatedAt)) {
            state = new ApplicationState();
            state.platformId = notice.platformId;
            state.applicationId = notice.applicationId;
            state.templateId = notice.key.templateId;
        }
        // 凭据恢复后才到达的首个创建通知只补齐生命周期边界，不能覆盖已恢复的新状态。
        if (created) state.createdAt = time;
        BusinessApplicationEventData<LifecyclePayload> data =
                notice.data(new TypeRef<BusinessApplicationEventData<LifecyclePayload>>() {});
        ApplicationInfo application = data.application;
        if (time >= state.updatedAt) {
            if (application.apiBaseUrl != null) {
                String base = application.apiBaseUrl;
                validateBase(base);
                state.apiBaseUrl = base;
            }
            if (application.state != null)
                state.enabled = ApplicationInfo.ENABLED.equals(application.state);
            if (APPLICATION_DISABLED.equals(type)) state.enabled = false;
            if (APPLICATION_ENABLED.equals(type) || created) state.enabled = true;
            state.updatedAt = time;
        }
        EncryptedCredential c = data.payload == null ? null : data.payload.credential;
        if ((created
                        || APPLICATION_CREDENTIAL_ROTATED.equals(type)
                        || APPLICATION_CREDENTIAL_SYNCED.equals(type))
                && c != null) {
            long revision = c.keyRevision == null ? -1 : c.keyRevision;
            if (revision < 1)
                throw new SdkException(Code.INVALID_ARGUMENT, "Invalid credential revision");
            if (state.accessSecret == null || revision > state.keyRevision) {
                state.accessKey = c.accessKey;
                state.accessSecret =
                        Signatures.decrypt(c.encryptedAccessSecret, privateKeys.apply(c.keyId));
                state.keyRevision = revision;
            }
        }
        return state;
    }

    private static void validateBase(String base) {
        ApiRequest.get("/").target(base);
        URI u = URI.create(base);
        if (!"https".equals(u.getScheme()) && !ArraysLoopback.isLoopback(u.getHost()))
            throw new SdkException(Code.INVALID_ARGUMENT, "Application API URL requires HTTPS");
    }

    private static class ArraysLoopback {
        static boolean isLoopback(String h) {
            return "localhost".equals(h) || "127.0.0.1".equals(h) || "[::1]".equals(h);
        }
    }
}
