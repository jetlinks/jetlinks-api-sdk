package org.jetlinks.sdk.spring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

/** Bounds servlet aggregation before Spring message converters allocate the entire request. */
final class ServletBodies {
    private ServletBodies() {}

    static byte[] read(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = request.getInputStream().read(chunk)) != -1) {
            if (result.size() + length > 1024 * 1024) throw new TooLarge();
            result.write(chunk, 0, length);
        }
        return result.toByteArray();
    }

    static final class TooLarge extends RuntimeException {}
}
