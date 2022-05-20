package org.jetlinks.sdk.client;

import org.jetlinks.sdk.model.ApiRequest;
import org.jetlinks.sdk.model.ApiResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * API客户端,用于发送API请求
 *
 * @author zhouhao
 * @since 1.0
 * @see WebApiClient
 */
public interface ApiClient {

    <T> ApiResponse<T> request(ApiRequest<T> request, Duration timeout);

    <T> ApiResponse<T> request(ApiRequest<T> request);

    <T> Mono<ApiResponse<T>> requestAsync(ApiRequest<T> request);

}
