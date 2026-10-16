package org.jetlinks.sdk.event;

/**
 * Known platform notification types. Catalog event IDs equal StructuredEvent.type;
 * availability for subscription is determined by the live catalog.
 * Event instance IDs are generated per occurrence; custom type strings remain supported.
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

    /** Custom types are delivered normally, but are not interpreted as application lifecycle. */
    public static boolean isApplicationEvent(String type) {
        return java.util.Arrays.asList(
                APPLICATION_CREATED, APPLICATION_UPDATED, APPLICATION_ENABLED,
                APPLICATION_DISABLED, APPLICATION_DELETED, APPLICATION_USER_BOUND,
                APPLICATION_USER_UNBOUND, APPLICATION_CREDENTIAL_ROTATED,
                APPLICATION_CREDENTIAL_SYNCED, APPLICATION_SYNC_CHANGED).contains(type);
    }

    private EventTypes() {}
}
