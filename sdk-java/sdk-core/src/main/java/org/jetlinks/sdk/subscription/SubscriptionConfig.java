package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The five writable subscription fields accepted by create/update/validate. @since 2.0 */
public class SubscriptionConfig {
    public String name, description, channelProvider;
    public List<SubscriptionScope> scopes = new ArrayList<>();
    public Map<String, Object> channelConfiguration;
}
