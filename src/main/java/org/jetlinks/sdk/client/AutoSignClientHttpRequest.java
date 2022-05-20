package org.jetlinks.sdk.client;

import io.netty.buffer.ByteBufAllocator;
import lombok.AllArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@AllArgsConstructor
public class AutoSignClientHttpRequest implements ClientHttpRequest {

    private final DataBufferFactory factory = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
    private final HttpMethod method;
    private final URI url;
    private final String clientId;
    private final byte[] secureKey;
    private final Supplier<MessageDigest> digest;
    private final HttpHeaders headers = new HttpHeaders();

    private final List<Function<ClientHttpRequest, Mono<Void>>> consumers = new ArrayList<>();

    ClientHttpRequest nativeRequest;

    public AutoSignClientHttpRequest(HttpMethod method,
                                     URI url,
                                     String clientId,
                                     byte[] secureKey,
                                     Supplier<MessageDigest> digest) {
        this.method = method;
        this.url = url;
        this.clientId = clientId;
        this.secureKey = secureKey;
        this.digest = digest;
    }

    @Override
    @Nonnull
    public DataBufferFactory bufferFactory() {
        return factory;
    }

    @Override
    public void beforeCommit(@Nonnull Supplier<? extends Mono<Void>> action) {
        consumers.add((request) -> {
            request.beforeCommit(action);
            return Mono.empty();
        });
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    @Nonnull
    public Mono<Void> writeWith(@Nonnull Publisher<? extends DataBuffer> body) {

        Flux<? extends DataBuffer> buffer = ApiUtils.cacheDataBuffer(Flux.from(body));

        consumers.add((req) -> req.writeWith(buffer));

        return ApiUtils
                .doSign(headers, clientId, secureKey, digest.get(), buffer)
                ;
    }

    @Override
    @Nonnull
    public Mono<Void> writeAndFlushWith(@Nonnull Publisher<? extends Publisher<? extends DataBuffer>> body) {

        Flux<? extends DataBuffer> buffer = ApiUtils.cacheDataBuffer(Flux.from(body).flatMap(Function.identity()));
        consumers.add((req) -> req.writeWith(buffer));

        return ApiUtils
                .doSign(headers, clientId, secureKey, digest.get(), buffer)
                ;
    }

    @Override
    @Nonnull
    public Mono<Void> setComplete() {
        consumers.add(ReactiveHttpOutputMessage::setComplete);
        return Mono.empty();
    }

    @Override
    @Nonnull

    public HttpHeaders getHeaders() {
        return headers;
    }


    @Override
    @Nonnull
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    @Nonnull
    public URI getURI() {
        return url;
    }

    @Override
    @Nonnull
    public MultiValueMap<String, HttpCookie> getCookies() {
        return new LinkedMultiValueMap<>();
    }

    @Override
    @Nonnull
    @SuppressWarnings("all")
    public <T> T getNativeRequest() {
        return (T) nativeRequest;
    }


    public Mono<Void> doInsert(ClientHttpRequest request) {
        this.nativeRequest = request;
        return Flux.fromIterable(consumers)
                   .concatMap(func -> func.apply(request))
                   .then();
    }
}
