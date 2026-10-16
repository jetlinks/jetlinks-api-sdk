package org.jetlinks.sdk.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves additional server fields without making known fields untyped. @since 2.0 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class Extensible {
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    @JsonAnySetter
    public void extension(String name, Object value) {
        extensions.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> extensions() {
        return extensions;
    }
}
