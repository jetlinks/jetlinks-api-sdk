package org.jetlinks.sdk.notification;

/**
 * Persisted third-party instance state, including lifecycle boundaries. Secrets must stay
 * server-side. @since 2.0
 */
public final class ApplicationState {
    public String platformId = "", applicationId, templateId, apiBaseUrl, accessKey, accessSecret;
    public long keyRevision, createdAt, updatedAt, deletedAt;
    public boolean enabled = true;

    public ApplicationState copy() {
        ApplicationState n = new ApplicationState();
        n.platformId = platformId;
        n.applicationId = applicationId;
        n.templateId = templateId;
        n.apiBaseUrl = apiBaseUrl;
        n.accessKey = accessKey;
        n.accessSecret = accessSecret;
        n.keyRevision = keyRevision;
        n.createdAt = createdAt;
        n.updatedAt = updatedAt;
        n.deletedAt = deletedAt;
        n.enabled = enabled;
        return n;
    }

    public boolean active() {
        return enabled
                && deletedAt == 0
                && accessKey != null
                && accessSecret != null
                && apiBaseUrl != null;
    }
}
