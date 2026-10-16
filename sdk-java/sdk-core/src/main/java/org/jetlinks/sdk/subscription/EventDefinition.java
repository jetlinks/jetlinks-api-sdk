package org.jetlinks.sdk.subscription;

import java.util.Map;

/** Event catalog entry. id is the type used in scopes and received events. @since 2.0 */
public class EventDefinition {
    public String id, name, group, description, version;
    public Map<String, Object> schema;
}
