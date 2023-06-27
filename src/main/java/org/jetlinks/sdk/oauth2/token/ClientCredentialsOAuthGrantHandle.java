package org.jetlinks.sdk.oauth2.token;

import lombok.Getter;
import org.jetlinks.sdk.oauth2.OAuth2Util;
import org.jetlinks.sdk.oauth2.client.OAuth2Config;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * @author gyl
 * @since 2.1
 */
public class ClientCredentialsOAuthGrantHandle extends AbstractOAuthGrantHandle {

    @Getter
    private final static String TYPE = "client_credentials";

    public ClientCredentialsOAuthGrantHandle(OAuth2Config oAuth2Config) {
        super(oAuth2Config);
    }

    @Override
    public Mono<OAuth2Config.OAuth2AccessToken> requestToken(WebClient webClient) {
        String redirectUri = oAuth2Config.getAppId();
        String clientSecret = oAuth2Config.getSecureKey();
        String clientId = oAuth2Config.getAppId();
        String tokenPath = oAuth2Config.getTokenPath();


        WebClient.RequestHeadersSpec<?> spec = webClient
                .post()
                .uri(tokenPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                              .fromFormData("client_id", clientId)
                              .with("client_secret", clientSecret)
                              .with("grant_type", TYPE)
                              .with("redirect_uri", redirectUri)
                              .with("oauth_timestamp", String.valueOf(System.currentTimeMillis()))
                );
        return OAuth2Util.request(spec);

    }
}
