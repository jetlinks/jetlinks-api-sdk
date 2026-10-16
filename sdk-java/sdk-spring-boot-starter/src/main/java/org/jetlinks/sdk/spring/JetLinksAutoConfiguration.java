package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.SdkException.Code;
import org.jetlinks.sdk.auth.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/**
 * 单客户端自动配置；仅在显式提供验签和处理 Bean 后开放接收端点。
 *
 * @since 2.0
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
                throw new SdkException(Code.INVALID_ARGUMENT, "Unsupported auth-mode");
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
