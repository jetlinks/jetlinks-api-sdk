package org.jetlinks.sdk.oauth2.client;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.validator.ValidatorUtils;
import org.jetlinks.sdk.oauth2.token.ClientCredentialsOAuthGrantHandle;
import org.jetlinks.sdk.oauth2.token.OAuthGrantHandle;
import org.springframework.util.unit.DataSize;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * @author gyl
 * @since 2.1
 */
@Slf4j
@Getter
@Setter
@Accessors(chain = true)
public class OAuth2Config implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 服务端地址
     */
    @NotBlank(message = "服务端地址不能为空")
    public String serverBaseUrl;

    /**
     * 本地回调地址
     */
    @NotBlank(message = "本地回调地址不能为空")
    private String redirectUri;

    /**
     * appId，服务端颁发
     */
    @NotBlank(message = "appId不能为空")
    private String appId;

    /**
     * secureKey，服务端颁发
     */
    @NotBlank(message = "secureKey不能为空")
    private String secureKey;

    /**
     * 服务端获取token接口路径
     */
    @NotBlank(message = "服务端token接口路径不能为空")
    private final String tokenPath = "/oauth2/token";

    /**
     * 服务端登录接口路径
     */
    @NotBlank(message = "服务端登录接口路径不能为空")
    private final String authorizationPath = "/oauth2/authorize";

    private String scope = "sdk";

    /**
     * 请求超时时间
     */
    private Duration requestTimeout = Duration.ofMinutes(1);

    /**
     * refresh_token刷新时间，默认30天
     */
    private Duration refreshExpireIn = Duration.ofDays(29);

    /**
     * 最大请求体
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(64);

    public OAuth2Config validate() {
        ValidatorUtils.tryValidate(this);
        return this;
    }

    @Getter
    @Setter
    public static class OAuth2AccessToken {
        private static final long serialVersionUID = 1L;
        @JsonProperty
        @JsonAlias("access_token")
        @JSONField(name = "access_token")
        private String accessToken;

        @JsonProperty
        @JsonAlias("refresh_token")
        @JSONField(name = "refresh_token")
        private String refreshToken;

        @JsonProperty
        @JsonAlias("expires_in")
        @JSONField(name = "expires_in")
        private int expiresIn;

        private long createTime = System.currentTimeMillis();

    }


}