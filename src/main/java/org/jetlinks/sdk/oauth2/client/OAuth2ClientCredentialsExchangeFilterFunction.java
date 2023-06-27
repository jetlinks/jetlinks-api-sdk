package org.jetlinks.sdk.oauth2.client;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetlinks.sdk.oauth2.token.ClientCredentialsOAuthGrantHandle;
import org.jetlinks.sdk.oauth2.token.OAuthGrantHandle;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.naming.AuthenticationException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * @author gyl
 * @since 2.1
 */
public class OAuth2ClientCredentialsExchangeFilterFunction implements ExchangeFilterFunction {

    protected final WebClient client;
    protected final OAuthGrantHandle oauthGrantHandle;

    private final static AtomicReferenceFieldUpdater<OAuth2ClientCredentialsExchangeFilterFunction, RefreshTokenCache>
            REFRESH_TOKEN = AtomicReferenceFieldUpdater.newUpdater(OAuth2ClientCredentialsExchangeFilterFunction.class, RefreshTokenCache.class, "refreshToken");

    private final Duration refreshExpireIn;
    private Mono<OAuth2Config.OAuth2AccessToken> tokenCache;
    protected volatile RefreshTokenCache refreshToken;

    public OAuth2ClientCredentialsExchangeFilterFunction(WebClient client,
                                                         OAuth2Config oauth2Config) {
        this.oauthGrantHandle = new ClientCredentialsOAuthGrantHandle(oauth2Config);
        this.client = client;
        this.refreshExpireIn = oauth2Config.getRefreshExpireIn();
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        return this
                .initRequestToken()
                .flatMap(token -> this.executeWithToken(token, request, next));
    }

    protected Mono<ClientResponse> executeWithToken(OAuth2Config.OAuth2AccessToken token,
                                                    @Nonnull ClientRequest request,
                                                    @Nonnull ExchangeFunction next) {
        return next
                .exchange(ClientRequest
                                  .from(request)
                                  .headers(headers -> headers.setBearerAuth(token.getAccessToken()))
                                  .build());
    }

    private Mono<OAuth2Config.OAuth2AccessToken> initRequestToken() {
        if (tokenCache == null) {
            this.tokenCache = requestToken()
                    .cache(a -> Duration.ofSeconds(a.getExpiresIn() - 30),
                           err -> Duration.ZERO,
                           () -> Duration.ZERO)
                    .doOnNext(token -> setRefreshToken(token.getRefreshToken()));
        }
        return tokenCache;
    }

    private Mono<OAuth2Config.OAuth2AccessToken> requestToken() {
        return Mono.defer(() -> Mono
                .justOrEmpty(REFRESH_TOKEN.get(this))
                .filter(RefreshTokenCache::notExpires)
                .flatMap(refreshToken -> oauthGrantHandle
                        .refreshToken(refreshToken.getRefreshToken(), client))
                .switchIfEmpty(doRequestToken())
        );
    }

    private Mono<OAuth2Config.OAuth2AccessToken> doRequestToken() {
        return oauthGrantHandle
                .requestToken(client)
                .switchIfEmpty(Mono.error(AuthenticationException::new));
    }

    private void setRefreshToken(String refreshToken) {
        long expTimestamp = System.currentTimeMillis() + refreshExpireIn.toMillis();
        REFRESH_TOKEN.set(this, RefreshTokenCache.of(refreshToken, expTimestamp));
    }


    @AllArgsConstructor(staticName = "of")
    @Getter
    private static class RefreshTokenCache {

        String refreshToken;
        /**
         * 到期时间戳
         */
        long expTimestamp;

        /**
         * @return refreshToken是否未过期
         */
        public boolean notExpires() {
            return System.currentTimeMillis() < expTimestamp;
        }

    }

}
