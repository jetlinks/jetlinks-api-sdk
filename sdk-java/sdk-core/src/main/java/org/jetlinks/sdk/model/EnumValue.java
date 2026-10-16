package org.jetlinks.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Map;

/**
 * 平台枚举值，同时接受标量 ID 和 value/text 对象。
 *
 * @since 2.0
 */
public class EnumValue {
    public String value, text;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public EnumValue(Object source) {
        if (source instanceof Map) {
            Map<?, ?> fields = (Map<?, ?>) source;
            value = fields.get("value") == null ? null : fields.get("value").toString();
            text = fields.get("text") == null ? null : fields.get("text").toString();
        } else {
            value = source == null ? null : source.toString();
        }
    }
}
