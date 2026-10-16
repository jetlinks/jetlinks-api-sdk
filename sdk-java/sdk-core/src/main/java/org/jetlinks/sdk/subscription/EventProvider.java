package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * 按来源提供方分组的事件定义。
 *
 * @since 2.0
 */
public class EventProvider {
    public String id, name;
    public List<EventDefinition> events = new ArrayList<>();
}
