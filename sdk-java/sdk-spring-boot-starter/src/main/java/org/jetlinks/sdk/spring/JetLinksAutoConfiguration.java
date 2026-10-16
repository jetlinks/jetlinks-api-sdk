package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.auth.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/**
 * Optional single-client auto configuration; no receiver is exposed without explicit verified
 * processing beans. @since 2.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JetLinksProperties.class)
@Import(ReceiverConfiguration.class)
public class JetLinksAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "jetlinks.sdk", name = "base-url")
    static class ClientConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        OAuth2 jetLinksOAuth2(JetLinksProperties p) {
            return new OAuth2(p.getBaseUrl(), p.getKey(), p.getSecret(), p.isBasic());
        }

        @Bean
        @ConditionalOnMissingBean(TokenProvider.class)
        TokenProvider jetLinksTokenProvider(JetLinksProperties p, OAuth2 oauth) {
            if ("local".equals(p.getAuthMode()))
                return OAuth2.localTokens(
                        p.getKey(), p.getSecret(), p.getScopes(), p.getLocalTokenTtl());
            if (!"oauth2".equals(p.getAuthMode()))
                throw new IllegalArgumentException("Unsupported auth-mode");
            return oauth.machineTokens(p.getScopes());
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        JetLinksReactiveClient jetLinksReactiveClient(
                JetLinksProperties p, TokenProvider provider) {
            return new JetLinksReactiveClient(
                    p.getBaseUrl(), provider, null, p.getRequestTimeout(), p.getMaxResponseBytes());
        }

        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean
        JetLinksClient jetLinksClient(
                JetLinksReactiveClient client, JetLinksProperties properties) {
            return new JetLinksClient(client, properties.getRequestTimeout().plusSeconds(5));
        }
    }
}
