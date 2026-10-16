package org.jetlinks.sdk.internal;

import reactor.core.publisher.*;

import java.io.ByteArrayOutputStream;

/**
 * Collects only explicitly bounded HTTP documents; streaming responses use the line decoder. @since
 * 2.0
 */
public final class BoundedBytes {
    private BoundedBytes() {}

    public static Mono<byte[]> collect(Flux<byte[]> source, int limit) {
        return source.collect(
                        ByteArrayOutputStream::new,
                        (out, bytes) -> {
                            if (bytes.length > limit - out.size())
                                throw new IllegalArgumentException("HTTP body exceeds limit");
                            out.write(bytes, 0, bytes.length);
                        })
                .map(ByteArrayOutputStream::toByteArray);
    }
}
