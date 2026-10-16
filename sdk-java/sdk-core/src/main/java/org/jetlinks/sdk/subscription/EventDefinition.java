package org.jetlinks.sdk.subscription;

import java.util.Map;

/**
 * 事件目录项；id 对应订阅范围和接收事件的 type。
 *
 * @since 2.0
 */
public class EventDefinition {
    public String id, name, group, description, version;
    public Map<String, Object> schema;
}
