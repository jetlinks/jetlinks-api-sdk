package org.jetlinks.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetlinks.sdk.Json;

import reactor.core.publisher.*;

import java.io.*;
import java.util.*;

/**
 * Frames bytes before UTF-8 decoding, so multibyte characters split across network chunks remain
 * intact. @since 2.0
 */
public final class Ndjson {
    private Ndjson() {}

    public static Flux<JsonNode> decode(Flux<byte[]> bytes, int limit) {
        return Flux.defer(
                () -> {
                    Decoder d = new Decoder(limit);
                    return bytes.concatMapIterable(d::accept)
                            .concatWith(Flux.defer(() -> Flux.fromIterable(d.finish())));
                });
    }

    private static final class Decoder {
        final int limit;
        final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Decoder(int limit) {
            this.limit = limit;
        }

        List<JsonNode> accept(byte[] bytes) {
            List<JsonNode> values = new ArrayList<>();
            for (byte b : bytes) {
                if (b == 10) {
                    emit(values);
                } else {
                    if (line.size() >= limit)
                        throw new IllegalArgumentException("NDJSON line exceeds limit");
                    line.write(b);
                }
            }
            return values;
        }

        void emit(List<JsonNode> values) {
            byte[] b = line.toByteArray();
            line.reset();
            if (b.length > 0 && !(b.length == 1 && b[0] == 13)) values.add(Json.parse(b));
        }

        List<JsonNode> finish() {
            List<JsonNode> values = new ArrayList<>();
            emit(values);
            return values;
        }
    }
}
