package org.jetlinks.sdk.internal;

import org.jetlinks.sdk.SdkException;
import org.jetlinks.sdk.SdkException.Code;

import reactor.core.publisher.*;

import java.io.ByteArrayOutputStream;

/**
 * 仅聚合具有明确大小限制的 HTTP 正文；流式响应交给逐行解码器。
 *
 * @since 2.0
 */
public final class BoundedBytes {
    private BoundedBytes() {}

    public static Mono<byte[]> collect(Flux<byte[]> source, int limit) {
        return source.collect(
                        ByteArrayOutputStream::new,
                        (out, bytes) -> {
                            if (bytes.length > limit - out.size())
                                throw new SdkException(
                                        Code.LIMIT_EXCEEDED, "HTTP body exceeds limit");
                            out.write(bytes, 0, bytes.length);
                        })
                .map(ByteArrayOutputStream::toByteArray);
    }
}
