package org.jetlinks.sdk.oauth2.token;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.sdk.oauth2.OAuth2Util;
import org.jetlinks.sdk.oauth2.client.OAuth2Config;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * @author gyl
 * @since 2.1
 */
@Slf4j
@AllArgsConstructor
public abstract class AbstractOAuthGrantHandle implements OAuthGrantHandle {

    protected final OAuth2Config oAuth2Config;


    public Mono<OAuth2Config.OAuth2AccessToken> refreshToken(String refreshToken,
                                                             WebClient webClient) {
        String clientSecret = oAuth2Config.getSecureKey();
        String clientId = oAuth2Config.getAppId();
        String redirectUri = oAuth2Config.getRedirectUri();


        WebClient.RequestHeadersSpec<?> spec;
        spec = webClient
                .post()
                .uri(oAuth2Config.getTokenPath())
                .body(BodyInserters
                              .fromFormData("client_id", clientId)
                              .with("client_secret", clientSecret)
                              .with("refresh_token", refreshToken)
                              .with("grant_type", "refresh_token")
                              .with("redirect_uri", redirectUri)
                              .with("oauth_timestamp", String.valueOf(System.currentTimeMillis()))
                );
        return OAuth2Util.request(spec);

    }

}
