package org.jetlinks.sdk.event;

import java.util.List;

/** Known lifecycle and synchronization payload fields; absent fields remain null. @since 2.0 */
public class LifecyclePayload {
    public EncryptedCredential credential;
    public List<String> userIds;
    public List<String> sections;

    public static final String MEMBERS = "members";
    public static final String ROLES = "roles";
    public static final String AUTHORIZATION = "authorization";
}
