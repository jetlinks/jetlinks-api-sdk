package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件定义 ID 及来源配置，与投递渠道配置分开管理。
 *
 * @since 2.0
 */
public class SubscriptionScope {
    public List<String> eventTypes = new ArrayList<>();
    public Map<String, Object> configuration = new LinkedHashMap<>();
}
