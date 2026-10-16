package org.jetlinks.sdk;

import java.net.*;
import java.util.*;

/**
 * Per-call request. Mutable builder; do not modify after submitting. Relative paths cannot replace
 * the trusted origin. @since 2.0
 */
public final class ApiRequest {
    public final String method, path;
    public final Map<String, String> headers = new LinkedHashMap<>(), query = new LinkedHashMap<>();
    public Object body;
    public boolean unwrap = true;

    private ApiRequest(String method, String path) {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.contains("?")
                || path.contains("#")
                || path.contains("\\"))
            throw new IllegalArgumentException("Expected relative API path");
        for (String part : path.split("/"))
            if (part.equals("..") || part.equals("."))
                throw new IllegalArgumentException("Path traversal");
        this.method = method.toUpperCase(Locale.ROOT);
        this.path = path;
    }

    public static ApiRequest get(String path) {
        return new ApiRequest("GET", path);
    }

    public static ApiRequest post(String path, Object value) {
        return new ApiRequest("POST", path).body(value);
    }

    public static ApiRequest of(String method, String path) {
        return new ApiRequest(method, path);
    }

    public ApiRequest body(Object value) {
        body = value;
        return this;
    }

    public ApiRequest header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public ApiRequest query(String name, Object value) {
        if (value != null) query.put(name, String.valueOf(value));
        return this;
    }

    public ApiRequest raw() {
        unwrap = false;
        return this;
    }

    public static String segment(String value) {
        return encode(value);
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String target(String base) {
        URI uri = URI.create(base);
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !Arrays.asList("http", "https").contains(uri.getScheme()))
            throw new IllegalArgumentException("Invalid API base URL");
        StringBuilder out = new StringBuilder(base.replaceAll("/+$", "")).append(path);
        query.forEach(
                (k, v) ->
                        out.append(out.indexOf("?") < 0 ? '?' : '&')
                                .append(encode(k))
                                .append('=')
                                .append(encode(v)));
        return out.toString();
    }

    public byte[] content() {
        if (body == null) return new byte[0];
        if (body instanceof byte[]) return (byte[]) body;
        return Json.bytes(body);
    }
}
