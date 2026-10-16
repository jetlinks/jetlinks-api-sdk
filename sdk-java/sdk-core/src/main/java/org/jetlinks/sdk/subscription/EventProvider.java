package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.List;

/** Event definitions grouped by their source provider. @since 2.0 */
public class EventProvider {
    public String id, name;
    public List<EventDefinition> events = new ArrayList<>();
}
