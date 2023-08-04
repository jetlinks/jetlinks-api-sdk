package org.jetlinks.sdk.client;

import org.jetlinks.sdk.model.ApiRequest;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.ApiResponseErrorException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * @author gyl
 * @since 2.1
 */
public abstract class AbstractApiClient implements ApiClient {

    abstract public WebClient getWebClient();

    abstract public Duration getRequestTimeout();

    @Override
    public <T> ApiResponse<T> request(ApiRequest<T> request) {
        return request(request, getRequestTimeout());
    }

    @Override
    public <T> ApiResponse<T> request(ApiRequest<T> request,
                                      Duration timeout) {
        return requestAsync(request).block(timeout);
    }

    @Override
    public <T> Mono<ApiResponse<T>> requestAsync(ApiRequest<T> request) {
        WebClient.RequestBodySpec spec = getWebClient()
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
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response
                                .bodyToMono(ParameterizedTypeReference.forType(
                                        ResolvableType
                                                .forClassWithGenerics(ApiResponse.class,
                                                                      request.responseType())
                                                .getType()
                                ));
                    }
                    return response
                            .<ApiResponse<Object>>bodyToMono(ParameterizedTypeReference.forType(
                                    ResolvableType
                                            .forClassWithGenerics(ApiResponse.class, Object.class)
                                            .getType()
                            ))
                            .flatMap(errBody -> Mono.error(() -> new ApiResponseErrorException(errBody)));
                })
                ;
    }
}
