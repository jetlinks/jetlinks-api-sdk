package org.jetlinks.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;

/**
 * SDK JSON boundary; domain APIs expose Java models. Thread safe after
 * construction. @since 2.0
 */
public final class Json {
    public static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {}

    public static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON value", e);
        }
    }

    public static JsonNode parse(byte[] value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException("Empty JSON document");
            return node;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convert(Object value, TypeReference<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T read(byte[] value, TypeReference<T> type) {
        try {
            T result = MAPPER.readValue(value, type);
            if (result == null) throw new IllegalArgumentException("Empty JSON document");
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid JSON", error);
        }
    }
}
