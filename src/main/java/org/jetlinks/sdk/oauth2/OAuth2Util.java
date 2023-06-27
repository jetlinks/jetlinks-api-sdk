package org.jetlinks.sdk.oauth2;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.sdk.oauth2.client.OAuth2Config;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author gyl
 * @since 2.1
 */
@Slf4j
public class OAuth2Util {

    public static Mono<OAuth2Config.OAuth2AccessToken> request(WebClient.RequestHeadersSpec<?> spec) {
        return OAuth2Util
                .doRequest(spec)
                .mapNotNull(OAuth2Util::parseToken);
    }

    public static Mono<String> doRequest(WebClient.RequestHeadersSpec<?> spec) {
        return spec
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response
                                .bodyToMono(String.class);
                    } else {
                        return response
                                .bodyToMono(String.class)
                                .doOnNext(e -> log.warn("request oauth2 [{}] token error {} ",
                                                        response.statusCode(),
                                                        e))
                                .then(Mono.empty());
                    }
                });
    }

    public static OAuth2Config.OAuth2AccessToken parseToken(String json) {
        OAuth2Config.OAuth2AccessToken token = JSON.parseObject(json, OAuth2Config.OAuth2AccessToken.class);
        return token.getAccessToken() == null ? null : token;
    }


    /**
     * 获取url中的query参数
     *
     * @param urlStr url
     * @param query  参数key
     * @return queryValue
     */
    public static Optional<String> getUrlQuery(String urlStr, String query) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = Pattern.compile(query + "=([^&]*)").matcher(urlStr);
        while (matcher.find()) {
            result.append(matcher.group(1));
        }
        return StringUtils.hasText(result.toString()) ? Optional.of(result.toString()) : Optional.empty();
    }

}
