package org.jetlinks.sdk.oauth2.client;

import lombok.Getter;
import org.jetlinks.sdk.client.AbstractApiClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * @author gyl
 * @since 2.1
 */
@Getter
public class WebOAuth2ApiClient extends AbstractApiClient {

    private final WebClient webClient;

    private final Duration requestTimeout;

    public WebOAuth2ApiClient(OAuth2Config config) {
        this.requestTimeout = config.getRequestTimeout();
        DataSize maxBodySize = config.getMaxBodySize();

        WebClient.Builder builder = WebClient
                .builder()
                .baseUrl(config.getServerBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(requestTimeout)))
                .codecs(conf -> conf.defaultCodecs()
                                    .maxInMemorySize((int) maxBodySize.toBytes()));

        this.webClient = builder
                .filter(new OAuth2ClientCredentialsExchangeFilterFunction(builder.build(), config))
                .build();
    }


}
