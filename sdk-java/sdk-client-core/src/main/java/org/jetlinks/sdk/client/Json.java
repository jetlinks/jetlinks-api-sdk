package org.jetlinks.sdk.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import org.jetlinks.sdk.client.SdkException.Code;

import java.io.IOException;

/**
 * SDK 共用的 JSON 编解码边界；业务接口使用 Java 模型。Mapper 完成配置后可并发使用。
 *
 * @since 2.0
 */
public final class Json {
    public static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    // 通用树/Map 解码也保留十进制值，避免类型化命令转换前已丢失精度。
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {}

    public static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new SdkException(Code.SERIALIZATION, "Invalid JSON value", e);
        }
    }

    public static JsonNode parse(byte[] value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || node.isMissingNode())
                throw new SdkException(Code.SERIALIZATION, "Empty JSON document");
            return node;
        } catch (IOException e) {
            throw new SdkException(Code.SERIALIZATION, "Invalid JSON", e);
        }
    }

    public static JsonNode tree(Object value) {
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException error) {
            throw new SdkException(Code.SERIALIZATION, "Cannot convert JSON value", error);
        }
    }

    public static <T> T convert(Object value, TypeReference<T> type) {
        try {
            return MAPPER.convertValue(value, type);
        } catch (IllegalArgumentException error) {
            throw new SdkException(Code.SERIALIZATION, "Cannot convert JSON value", error);
        }
    }

    public static <T> T convert(Object value, Class<T> type) {
        return convert(value, TypeRef.of(type));
    }

    public static <T> T read(byte[] value, TypeReference<T> type) {
        try {
            T result = MAPPER.readValue(value, type);
            if (result == null) throw new SdkException(Code.SERIALIZATION, "Empty JSON document");
            return result;
        } catch (IOException error) {
            throw new SdkException(Code.SERIALIZATION, "Invalid JSON", error);
        }
    }
}
