package org.jetlinks.sdk;

import org.jetlinks.sdk.SdkException.Code;

import java.util.*;

/**
 * 独立的平台查询参数，支持自定义条件，无需引入 hsweb。
 *
 * @since 2.0
 */
public final class Query {
    public int pageIndex = 0, pageSize = 50;
    public final List<Map<String, Object>> terms = new ArrayList<>(), sorts = new ArrayList<>();

    public Query page(int index, int size) {
        if (index < 0 || size < 1 || size > 1000)
            throw new SdkException(Code.INVALID_ARGUMENT, "Invalid page");
        pageIndex = index;
        pageSize = size;
        return this;
    }

    public Query term(String column, String type, Object value) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("column", column);
        t.put("termType", type);
        t.put("value", value);
        terms.add(t);
        return this;
    }

    public Query eq(String column, Object value) {
        return term(column, "eq", value);
    }

    public Query sort(String name, String order) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("order", order);
        sorts.add(s);
        return this;
    }

    public Query copy() {
        Query copy = new Query();
        copy.pageIndex = pageIndex;
        copy.pageSize = pageSize;
        terms.forEach(term -> copy.terms.add(new LinkedHashMap<>(term)));
        sorts.forEach(sort -> copy.sorts.add(new LinkedHashMap<>(sort)));
        return copy;
    }
}
