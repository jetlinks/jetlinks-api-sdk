package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Event definition IDs and source-specific configuration, separate from delivery configuration. @since 2.0 */
public class SubscriptionScope {
    public List<String> eventTypes = new ArrayList<>();
    public Map<String, Object> configuration = new LinkedHashMap<>();
}
