package org.jetlinks.sdk.oauth2.token;

import org.jetlinks.sdk.oauth2.client.OAuth2Config;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * @author gyl
 * @since 2.1
 */
public interface OAuthGrantHandle {


    /**
     * 请求token
     *
     * @return 新的token
     */
    Mono<OAuth2Config.OAuth2AccessToken> requestToken(WebClient webClient);

    /**
     * 刷新token
     *
     * @return 新的token
     */
    Mono<OAuth2Config.OAuth2AccessToken> refreshToken(String refreshToken, WebClient webClient);


}
