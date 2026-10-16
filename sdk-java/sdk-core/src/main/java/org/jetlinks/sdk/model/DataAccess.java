package org.jetlinks.sdk.model;

import java.util.Map;

/** Permission data-access rule; config is interpreted by its type's provider. @since 2.0 */
public class DataAccess extends Extensible {
    public String action, type, describe;
    public Map<String, Object> config;
}
