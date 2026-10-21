package org.jetlinks.sdk.client.command;

import java.util.Set;

/** 单个资源授予的功能集合；空集合表示撤销该资源的受管功能。 @since 2.0 */
public class ResourceCapability {
    public String resource;
    public Set<String> capabilities;

    public ResourceCapability() {}

    public ResourceCapability(String resource, Set<String> capabilities) {
        this.resource = resource;
        this.capabilities = capabilities;
    }
}
