package org.jetlinks.sdk.event;

/**
 * 平台已知通知类型；事件目录 ID 与事件 type 相同，可订阅性以实时目录为准。事件实例 ID 每次生成，自定义类型仍使用字符串。
 *
 * @since 2.0
 */
public final class EventTypes {
    public static final String APPLICATION_CREATED = "application.created";
    public static final String APPLICATION_UPDATED = "application.updated";
    public static final String APPLICATION_ENABLED = "application.enabled";
    public static final String APPLICATION_DISABLED = "application.disabled";
    public static final String APPLICATION_DELETED = "application.deleted";
    public static final String APPLICATION_USER_BOUND = "application.user.bound";
    public static final String APPLICATION_USER_UNBOUND = "application.user.unbound";
    public static final String APPLICATION_CREDENTIAL_ROTATED = "application.credential.rotated";
    public static final String APPLICATION_CREDENTIAL_SYNCED = "application.credential.synced";
    public static final String APPLICATION_SYNC_CHANGED = "application.sync.changed";

    /** 自定义类型正常投递，但不参与应用生命周期合并。 */
    public static boolean isApplicationEvent(String type) {
        return java.util.Arrays.asList(
                        APPLICATION_CREATED,
                        APPLICATION_UPDATED,
                        APPLICATION_ENABLED,
                        APPLICATION_DISABLED,
                        APPLICATION_DELETED,
                        APPLICATION_USER_BOUND,
                        APPLICATION_USER_UNBOUND,
                        APPLICATION_CREDENTIAL_ROTATED,
                        APPLICATION_CREDENTIAL_SYNCED,
                        APPLICATION_SYNC_CHANGED)
                .contains(type);
    }

    private EventTypes() {}
}
