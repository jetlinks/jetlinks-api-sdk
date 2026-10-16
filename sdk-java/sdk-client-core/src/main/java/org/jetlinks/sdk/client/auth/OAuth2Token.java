package org.jetlinks.sdk.client.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth2 原始响应模型；SDK 不自动保存用户 Token。
 *
 * @since 2.0
 */
public class OAuth2Token {
    @JsonProperty("access_token")
    public String accessToken;

    @JsonProperty("token_type")
    public String tokenType;

    @JsonProperty("expires_in")
    public long expiresIn;

    @JsonProperty("refresh_token")
    public String refreshToken;

    public String scope;
}
