package org.jetlinks.sdk;

import org.jetlinks.sdk.SdkException.Code;

import java.net.*;
import java.util.*;

/**
 * 单次调用的可变请求构造器，提交后不可继续修改；相对路径不能替换客户端绑定的可信地址。
 *
 * @since 2.0
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
            throw new SdkException(Code.INVALID_ARGUMENT, "Expected relative API path");
        for (String part : path.split("/"))
            if (part.equals("..") || part.equals("."))
                throw new SdkException(Code.INVALID_ARGUMENT, "Path traversal");
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

    /** 关闭平台 result 解包，适用于 OAuth2 等原生响应。 */
    public ApiRequest raw() {
        unwrap = false;
        return this;
    }

    /** 编码单个动态路径段，避免业务 ID 改变 URL 结构。 */
    public static String segment(String value) {
        return encode(value);
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new SdkException(Code.INVALID_STATE, "Cannot encode UTF-8 value", e);
        }
    }

    public String target(String base) {
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException error) {
            throw new SdkException(Code.INVALID_ARGUMENT, "Invalid API base URL", error);
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !Arrays.asList("http", "https").contains(uri.getScheme()))
            throw new SdkException(Code.INVALID_ARGUMENT, "Invalid API base URL");
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
