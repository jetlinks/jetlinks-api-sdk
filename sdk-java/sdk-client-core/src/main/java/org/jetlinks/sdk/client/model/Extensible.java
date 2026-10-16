package org.jetlinks.sdk.client.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保留服务端新增字段，同时让已知字段保持明确类型。
 *
 * @since 2.0
 */
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
