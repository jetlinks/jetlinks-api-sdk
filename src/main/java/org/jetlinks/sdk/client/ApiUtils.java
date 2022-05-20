package org.jetlinks.sdk.client;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.jetlinks.core.message.codec.http.HttpUtils;
import org.jetlinks.sdk.ApiConstants;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
public class ApiUtils {

    public static String signByQuery(String timestamp,
                                     byte[] secureKey,
                                     MessageDigest digest,
                                     Map<String, String> query) {
        byte[] param = new TreeMap<>(query)
                .entrySet()
                .stream()
                .filter(e -> e.getKey() != null)
                .map(e -> e.getKey().concat("=").concat(e.getValue() == null ? "" : e.getValue()))
                .collect(Collectors.joining("&"))
                .getBytes();
        digest.update(param);
        digest.update(timestamp.getBytes());
        digest.update(secureKey);

        return Hex.encodeHexString(digest.digest());
    }

    public static Mono<Void> doSign(HttpHeaders headers,
                                    String clientId,
                                    byte[] secureKey,
                                    MessageDigest digest,
                                    Publisher<? extends DataBuffer> buffers) {
        String time = String.valueOf(System.currentTimeMillis());

        headers.add(ApiConstants.Headers.clientId, clientId);
        headers.add(ApiConstants.Headers.timestamp, time);

        //POST application/x-www-form-urlencoded
        if (MediaType.APPLICATION_FORM_URLENCODED.includes(headers.getContentType())) {
            return DataBufferUtils
                    .join(buffers)
                    .map(buf -> {
                        String sign = signByQuery(time, secureKey, digest, HttpUtils.parseEncodedUrlParams(
                                buf.toString(StandardCharsets.UTF_8)
                        ));
                        headers.add(ApiConstants.Headers.sign, sign);
                        return 1;
                    })
                    .then();
        }

        return Flux
                .from(buffers)
                .map(buf -> {
                    digest.update(buf.asByteBuffer());
                    return 1;
                })
                .then(Mono.fromRunnable(() -> {
                    digest.update(time.getBytes());
                    digest.update(secureKey);
                    headers.add(ApiConstants.Headers.sign, Hex.encodeHexString(digest.digest()));
                }));
    }

    public static Flux<? extends DataBuffer> cacheDataBuffer(Publisher<? extends DataBuffer> buffer) {
        return Flux
                .usingWhen(DataBufferUtils.join(Flux.from(buffer)).cache(),
                           dataBuffer -> Mono.just(DataBufferUtils.retain(dataBuffer)),
                           dataBuffer -> {
                               DataBufferUtils.release(dataBuffer);
                               return Mono.empty();
                           }
                );
    }

}
