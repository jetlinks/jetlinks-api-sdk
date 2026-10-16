package org.jetlinks.sdk;

import com.fasterxml.jackson.core.type.TypeReference;

/** Retains nested generic response types without any platform model dependency. @since 2.0 */
public abstract class TypeRef<T> extends TypeReference<T> {
    public static <T> TypeRef<T> of(Class<T> type) {
        java.util.Objects.requireNonNull(type, "type");
        return new TypeRef<T>() {
            @Override
            public java.lang.reflect.Type getType() {
                return type;
            }
        };
    }
}
