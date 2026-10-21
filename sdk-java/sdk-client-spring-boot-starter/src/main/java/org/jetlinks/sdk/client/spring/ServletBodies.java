package org.jetlinks.sdk.client.spring;

import jakarta.servlet.http.HttpServletRequest;

import org.jetlinks.sdk.client.SdkException;
import org.jetlinks.sdk.client.SdkException.Code;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 在 Spring 消息转换器聚合请求前限制 Servlet 正文大小。
 *
 * @since 2.0
 */
final class ServletBodies {
    private ServletBodies() {}

    static byte[] read(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = request.getInputStream().read(chunk)) != -1) {
            if (result.size() + length > 1024 * 1024)
                throw new SdkException(Code.LIMIT_EXCEEDED, "HTTP body exceeds limit");
            result.write(chunk, 0, length);
        }
        return result.toByteArray();
    }
}
