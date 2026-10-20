package org.jetlinks.sdk.client;

import java.util.*;
import java.util.function.Consumer;

/**
 * 单次范围配置构造器；不同维度取交集，同维度后设置覆盖前设置。仅选择资产，不授予权限。
 *
 * @since 2.0
 */
public final class AssetScope {
    public static final String HEADER = "X-Asset-Scope";
    private final Map<String, String> values = new LinkedHashMap<>();

    public AssetScope organization(String id) {
        return scope("org", id);
    }

    public AssetScope tenant(String id) {
        return scope("tenant", id);
    }

    /** 自定义维度；type 不可含冒号，ID 可以含冒号。 */
    public AssetScope scope(String type, String id) {
        validate(type, 64);
        validate(id, 256);
        if (type.indexOf(':') >= 0) throw invalid();
        if (!values.containsKey(type) && values.size() >= 16) throw invalid();
        values.put(type, id);
        return this;
    }

    private static void validate(String value, int max) {
        if (value == null
                || value.isEmpty()
                || value.length() > max
                || boundarySpace(value.charAt(0))
                || boundarySpace(value.charAt(value.length() - 1))
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }

    private static boolean boundarySpace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\ufeff';
    }

    private static SdkException invalid() {
        return new SdkException(SdkException.Code.INVALID_ARGUMENT, "Invalid asset scope");
    }

    static Map<String, String> configure(
            Map<String, String> defaults, Consumer<AssetScope> action) {
        if (action == null) throw invalid();
        AssetScope scope = new AssetScope();
        defaults.forEach(scope::scope);
        action.accept(scope);
        return Collections.unmodifiableMap(new LinkedHashMap<>(scope.values));
    }

    static List<String> headers(
            List<String> inherited,
            Map<String, String> defaults,
            Map<String, String> raw,
            Map<String, String> overrides) {
        AssetScope scope = new AssetScope();
        inherited.forEach(scope::header);
        defaults.forEach(scope::scope);
        raw.forEach(
                (name, value) -> {
                    if (HEADER.equalsIgnoreCase(name)) {
                        scope.header(value);
                    }
                });
        overrides.forEach(scope::scope);
        List<String> result = new ArrayList<>();
        scope.values.forEach((type, id) -> result.add(type + ":" + id));
        return result;
    }

    private void header(String value) {
        int colon = value == null ? -1 : value.indexOf(':');
        if (colon < 1) throw invalid();
        scope(value.substring(0, colon), value.substring(colon + 1));
    }
}
