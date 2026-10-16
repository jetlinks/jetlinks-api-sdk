package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建、更新和校验接口接受的五个订阅配置字段。
 *
 * @since 2.0
 */
public class SubscriptionConfig {
    public String name, description, channelProvider;
    public List<SubscriptionScope> scopes = new ArrayList<>();
    public Map<String, Object> channelConfiguration;
}
