package org.jetlinks.sdk.client;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.sdk.model.ApiRequest;
import org.jetlinks.sdk.model.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
public class WebApiClient implements ApiClient {

    private final WebClient apiClient;

    private final Duration requestTimeout;

    public WebApiClient(ClientConfig clientConfig) {
        this.requestTimeout = clientConfig.getRequestTimeout();
        DataSize maxBodySize = clientConfig.getMaxBodySize();

        WebClient signClient = WebClient
                .builder()
                .baseUrl(clientConfig.getBaseUrl())
                .codecs(conf -> conf.defaultCodecs()
                                .maxInMemorySize((int) maxBodySize.toBytes()))
                .filter(new AutoSignExchangeFilterFunction(
                        clientConfig.getClientId(),
                        clientConfig.getSecureKey().getBytes(StandardCharsets.UTF_8),
                        clientConfig.getSignature()::getMessageDigest))
                .build();

        if (clientConfig.isTokenMod()) {
            apiClient = WebClient
                    .builder()
                    .codecs(conf -> conf.defaultCodecs()
                                    .maxInMemorySize((int) maxBodySize.toBytes()))
                    .baseUrl(clientConfig.getBaseUrl())
                    .filter(new TokenModRequestFilterFunction(signClient))
                    .build();
        } else {
            apiClient = signClient;
        }
    }

    private static class TokenModRequestFilterFunction implements ExchangeFilterFunction {
        private final WebClient tokenClient;

        private volatile Mono<TokenInfo> token;

        public TokenModRequestFilterFunction(WebClient tokenClient) {
            this.tokenClient = tokenClient;
            initToken();
        }

        private void initToken() {
            token = tokenClient
                    .post()
                    .uri("token/default")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<ApiResponse<TokenInfo>>() {
                    })
                    .<TokenInfo>handle((resp, sink) -> {
                        if (resp.isSuccess()) {
                            if (resp.getResult() == null || resp.getResult().token == null) {
                                sink.error(new IllegalArgumentException("request token error"));
                            } else {
                                log.debug("request api token success,expires:{}s", resp.getResult().expires);
                                sink.next(resp.getResult());
                            }
                        } else {
                            sink.error(new IllegalArgumentException(resp.getMessage()));
                        }
                    })
                    .cache(val -> Duration.ofSeconds(val.expires - 10),
                           err -> Duration.ZERO, () -> Duration.ZERO);
        }

        @Override
        @Nonnull
        public Mono<ClientResponse> filter(@Nonnull ClientRequest request, ExchangeFunction next) {
            return token
                    .map(token -> ClientRequest
                            .from(request)
                            .header("X-Access-Token", token.getToken())
                            .build())
                    .flatMap(next::exchange)
                    .doOnNext(response -> {
                        if (response.statusCode() == HttpStatus.UNAUTHORIZED) {
                            initToken();
                        }
                    });
        }
    }

    @Getter
    @Setter
    public static class TokenInfo {
        private String token;
        private long expires = 3600;
    }

    @Override
    public <T> ApiResponse<T> request(ApiRequest<T> request) {
        return request(request, requestTimeout);
    }

    @Override
    public <T> ApiResponse<T> request(ApiRequest<T> request,
                                      Duration timeout) {
        return requestAsync(request).block(timeout);
    }

    @Override
    public <T> Mono<ApiResponse<T>> requestAsync(ApiRequest<T> request) {
        WebClient.RequestBodySpec spec = apiClient
                .method(request.method())
                .uri(request.uri(), builder -> {
                    if (null != request.parameters()) {
                        return builder
                                .queryParams(request.parameters())
                                .build();
                    }
                    return builder.build();
                });
        if (request.method() == HttpMethod.PATCH
                || request.method() == HttpMethod.POST
                || request.method() == HttpMethod.PUT) {
            if (request.body() != null) {
                spec.contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.body());
            }
        }
        return spec
                .exchangeToMono(response -> response
                        .bodyToMono(ParameterizedTypeReference.forType(
                                ResolvableType
                                        .forClassWithGenerics(ApiResponse.class,
                                                              request.responseType())
                                        .getType()
                        )))
                ;
    }


}
