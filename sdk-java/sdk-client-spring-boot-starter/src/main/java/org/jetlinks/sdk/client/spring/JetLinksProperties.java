package org.jetlinks.sdk.client.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 单客户端默认配置；多应用凭据通过核心 resolver/store SPI 提供。
 *
 * @since 2.0
 */
@ConfigurationProperties("jetlinks.sdk")
public class JetLinksProperties {
    private String baseUrl, key, secret, authMode = "oauth2";
    private List<String> scopes;
    private boolean basic = true;
    private Duration requestTimeout = Duration.ofSeconds(30), localTokenTtl = Duration.ofMinutes(2);
    private int maxResponseBytes = 1024 * 1024;

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration value) {
        requestTimeout = value;
    }

    public Duration getLocalTokenTtl() {
        return localTokenTtl;
    }

    public void setLocalTokenTtl(Duration value) {
        localTokenTtl = value;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int value) {
        maxResponseBytes = value;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String v) {
        key = v;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String v) {
        secret = v;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String v) {
        authMode = v;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> v) {
        scopes = v;
    }

    public boolean isBasic() {
        return basic;
    }

    public void setBasic(boolean v) {
        basic = v;
    }
}
