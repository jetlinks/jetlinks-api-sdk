package org.jetlinks.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Bounded raw HTTP result. Header values remain multi-valued for diagnostic/protocol use. @since
 * 2.0
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
        } catch (IllegalArgumentException error) {
            // Gateways may return HTML/text failures; retain the HTTP status for callers.
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
                throw new IllegalStateException("Missing platform result envelope");
            return n.get("result");
        }
        return n;
    }
}
