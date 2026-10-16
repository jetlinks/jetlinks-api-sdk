package org.jetlinks.sdk.notification;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.crypto.Signatures;
import org.jetlinks.sdk.event.*;

import static org.jetlinks.sdk.event.EventTypes.*;

import java.net.URI;
import java.util.function.Function;

/**
 * Pure lifecycle merge. Caller persists this result and inbox in one transaction; no cache or key
 * rotation occurs here. @since 2.0
 */
public final class ApplicationLifecycle {
    private ApplicationLifecycle() {}

    public static ApplicationState apply(
            ApplicationState current,
            VerifiedNotification notice,
            Function<String, String> privateKeys) {
        ApplicationState state = current == null ? new ApplicationState() : current.copy();
        if (current != null
                && (!notice.platformId.equals(current.platformId)
                        || !notice.applicationId.equals(current.applicationId)
                        || !notice.key.templateId.equals(current.templateId)))
            throw new SecurityException("Existing ownership conflict");
        state.platformId = notice.platformId;
        state.applicationId = notice.applicationId;
        state.templateId = notice.key.templateId;
        String type = notice.event.type == null ? "" : notice.event.type;
        if (!EventTypes.isApplicationEvent(type)) return state;
        long time = notice.event.occurredAt == null ? -1 : notice.event.occurredAt;
        if (time < 0) throw new IllegalArgumentException("Missing lifecycle occurredAt");
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
        // Synced/updated cannot resurrect a deleted instance. A later creation also starts a new
        // lifecycle when the old deletion has not arrived; revisions belong to that lifecycle.
        if (state.deletedAt > 0 && (!created || time <= state.deletedAt)) return state;
        if (created && time > state.createdAt
                && (state.createdAt > 0 || time > state.updatedAt)) {
            state = new ApplicationState();
            state.platformId = notice.platformId;
            state.applicationId = notice.applicationId;
            state.templateId = notice.key.templateId;
        }
        // A first creation delivered after credential recovery only establishes the boundary;
        // it must preserve the newer state already recovered from the same lifecycle.
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
            if (APPLICATION_ENABLED.equals(type) || created)
                state.enabled = true;
            state.updatedAt = time;
        }
        EncryptedCredential c = data.payload == null ? null : data.payload.credential;
        if ((created || APPLICATION_CREDENTIAL_ROTATED.equals(type)
                        || APPLICATION_CREDENTIAL_SYNCED.equals(type)) && c != null) {
            long revision = c.keyRevision == null ? -1 : c.keyRevision;
            if (revision < 1) throw new IllegalArgumentException("Invalid credential revision");
            if (state.accessSecret == null || revision > state.keyRevision) {
                state.accessKey = c.accessKey;
                state.accessSecret =
                        Signatures.decrypt(
                                c.encryptedAccessSecret,
                                privateKeys.apply(c.keyId));
                state.keyRevision = revision;
            }
        }
        return state;
    }

    private static void validateBase(String base) {
        ApiRequest.get("/").target(base);
        URI u = URI.create(base);
        if (!"https".equals(u.getScheme()) && !ArraysLoopback.isLoopback(u.getHost()))
            throw new IllegalArgumentException("Application API URL requires HTTPS");
    }

    private static class ArraysLoopback {
        static boolean isLoopback(String h) {
            return "localhost".equals(h) || "127.0.0.1".equals(h) || "[::1]".equals(h);
        }
    }
}
