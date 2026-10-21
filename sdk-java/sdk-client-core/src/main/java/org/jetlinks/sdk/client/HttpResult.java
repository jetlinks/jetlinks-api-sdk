package org.jetlinks.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetlinks.sdk.client.SdkException.Code;

import java.util.*;

/**
 * 大小受限的原始 HTTP 响应；Header 保留多值，供诊断和协议校验。
 *
 * @since 2.0
 */
public final class HttpResult {
    public final int status;
    public final Map<String, List<String>> headers;
    public final byte[] body;

    public HttpResult(int status, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public JsonNode json() {
        if (body.length == 0) return Json.MAPPER.nullNode();
        try {
            return Json.parse(body);
        } catch (SdkException error) {
            // 网关可能返回 HTML 或文本错误，此时保留 HTTP 状态供调用方判断。
            if (status >= 400)
                return Json.MAPPER
                        .getNodeFactory()
                        .textNode(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            throw error;
        }
    }

    public JsonNode value(boolean unwrap) {
        JsonNode n = json();
        int code = n.path("status").asInt(status);
        if (status >= 400 || code >= 400) throw new SdkException(status >= 400 ? status : code, n);
        if (unwrap) {
            if (!n.has("result"))
                throw new SdkException(Code.SERIALIZATION, "Missing platform result envelope");
            return n.get("result");
        }
        return n;
    }
}
