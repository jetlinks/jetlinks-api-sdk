package org.jetlinks.sdk.client.event;

import java.util.List;

/**
 * 已知生命周期及同步事件的载荷；未提供的字段保持 null。
 *
 * @since 2.0
 */
public class LifecyclePayload {
    public EncryptedCredential credential;
    public List<String> userIds;
    public List<String> sections;

    public static final String MEMBERS = "members";
    public static final String ROLES = "roles";
    public static final String AUTHORIZATION = "authorization";
}
