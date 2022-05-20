package org.jetlinks.sdk.client;

import lombok.AllArgsConstructor;
import org.jetlinks.core.message.codec.http.HttpUtils;
import org.jetlinks.sdk.ApiConstants;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@AllArgsConstructor
public class AutoSignExchangeFilterFunction implements ExchangeFilterFunction {
    private final String clientId;
    private final byte[] secureKey;
    private final Supplier<MessageDigest> digest;

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request,@Nonnull ExchangeFunction next) {
        ClientRequest.Builder builder = ClientRequest.from(request);
        //GET,DELETE,OPTIONS使用url中的query参数来进行签名
        if (request.method() == HttpMethod.GET
                || request.method() == HttpMethod.DELETE
                || request.method() == HttpMethod.OPTIONS) {
            signByQuery(request, builder);
            return next.exchange(builder.build());
        } else {
            //使用request body进行签名
            BodyInserter<?, ? super ClientHttpRequest> inserter = request.body();

            //模拟一个Request来获取body并生成签名信息.
            AutoSignClientHttpRequest signRequest = new AutoSignClientHttpRequest(
                    request.method(),
                    request.url(),
                    clientId, secureKey, digest);
            signRequest.getHeaders().addAll(request.headers());

            return inserter
                    .insert(signRequest, CONTEXT)
                    .then(
                            Mono.fromSupplier(() -> builder
                                    //将签名的请求头填充到请求中
                                    .headers(headers -> headers.addAll(signRequest.getHeaders()))
                                    .body((outputMessage, context) -> signRequest.doInsert(outputMessage))
                                    .build())
                    )
                    .flatMap(next::exchange);
        }
    }

    private void signByQuery(ClientRequest request, ClientRequest.Builder builder) {
        String query = request.url().getQuery();

        String timestamp = String.valueOf(System.currentTimeMillis());

        builder.header(ApiConstants.Headers.clientId, clientId);
        builder.header(ApiConstants.Headers.timestamp, timestamp);
        builder.header(ApiConstants.Headers.sign, ApiUtils
                .signByQuery(timestamp,
                             secureKey,
                             digest.get(),
                             HttpUtils.parseEncodedUrlParams(query)));
    }

    private static final BodyInserter.Context CONTEXT = new BodyInserter.Context() {
        @Override
        @Nonnull
        public List<HttpMessageWriter<?>> messageWriters() {
            return ExchangeStrategies.withDefaults().messageWriters();
        }

        @Override
        @Nonnull
        public Optional<ServerHttpRequest> serverRequest() {
            return Optional.empty();
        }

        @Override
        @Nonnull
        public Map<String, Object> hints() {
            return Collections.emptyMap();
        }
    };
}
