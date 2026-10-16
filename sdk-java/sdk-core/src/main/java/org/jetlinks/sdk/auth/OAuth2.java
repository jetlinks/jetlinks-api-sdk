package org.jetlinks.sdk.auth;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.SdkException.Code;
import org.jetlinks.sdk.crypto.Signatures;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * 绑定单一服务地址和凭据的 OAuth2 客户端；用户授权显式调用，不缓存或重试授权码兑换。
 *
 * @since 2.0
 */
public final class OAuth2 implements AutoCloseable {
    private final JetLinksReactiveClient transport;
    private final String key, secret;
    private final boolean basic;

    public OAuth2(String base, String key, String secret, boolean basic) {
        this.transport = new JetLinksReactiveClient(base, TokenProvider.anonymous());
        this.key = key;
        this.secret = secret;
        this.basic = basic;
    }

    /** 申请机器 Token；null scopes 保留平台默认值，空集合明确申请空 Scope。 */
    public Mono<OAuth2Token> machine(List<String> scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scopes != null) form.put("scope", String.join(" ", scopes));
        return token(form);
    }

    /** 使用授权码及可选 PKCE verifier 兑换用户 Token；不自动重试。 */
    public Mono<OAuth2Token> exchange(String code, String redirectUri, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        if (verifier != null) form.put("code_verifier", verifier);
        return token(form);
    }

    private Mono<OAuth2Token> token(Map<String, String> form) {
        ApiRequest request =
                ApiRequest.post("/oauth2/token", null)
                        .raw()
                        .header("Content-Type", "application/x-www-form-urlencoded");
        if (basic)
            request.header(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (key + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        else {
            form.put("client_id", key);
            form.put("client_secret", secret);
        }
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(ApiRequest.encode(k) + "=" + ApiRequest.encode(v)));
        request.body(body.toString().getBytes(StandardCharsets.UTF_8));
        return transport
                .request(request, OAuth2Token.class)
                .switchIfEmpty(
                        Mono.error(
                                () ->
                                        new SdkException(
                                                Code.SERIALIZATION,
                                                "Invalid OAuth2 token response")))
                .map(
                        n -> {
                            if (n.accessToken == null || n.expiresIn <= 0)
                                throw new SdkException(
                                        Code.SERIALIZATION, "Invalid OAuth2 token response");
                            return n;
                        });
    }

    /** 为当前身份和 Scope 创建独立的并发合并缓存。 */
    public CachedTokenProvider machineTokens(List<String> scopes) {
        return new CachedTokenProvider(
                () ->
                        machine(scopes)
                                .map(
                                        n ->
                                                new CachedTokenProvider.Issued(
                                                        n.accessToken,
                                                        System.currentTimeMillis()
                                                                + n.expiresIn * 1000)),
                Duration.ofSeconds(10));
    }

    /** 使用本地签名生成机器 Token，并按配置有效期缓存；不会请求 OAuth2。 */
    public static CachedTokenProvider localTokens(
            String key, String secret, List<String> scopes, Duration ttl) {
        return new CachedTokenProvider(
                () ->
                        Mono.fromSupplier(
                                () ->
                                        new CachedTokenProvider.Issued(
                                                LocalToken.create(
                                                        key,
                                                        secret,
                                                        scopes,
                                                        System.currentTimeMillis()),
                                                System.currentTimeMillis() + ttl.toMillis())),
                Duration.ofSeconds(5));
    }

    /** 生成带 state 和 PKCE 的授权跳转信息；调用方负责绑定浏览器会话。 */
    public Authorization authorization(String redirectUri, List<String> scopes) {
        String state = random(), verifier = random();
        String challenge;
        try {
            challenge =
                    Signatures.base64(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot compute credential signature", e);
        }
        ApiRequest request =
                ApiRequest.get("/oauth2/authorize")
                        .query("response_type", "code")
                        .query("client_id", key)
                        .query("redirect_uri", redirectUri)
                        .query("state", state)
                        .query("code_challenge", challenge)
                        .query("code_challenge_method", "S256");
        if (scopes != null) request.query("scope", String.join(" ", scopes));
        return new Authorization(request.target(transport.baseUrl()), state, verifier, redirectUri);
    }

    private static String random() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Signatures.base64(value);
    }

    public void close() {
        transport.close();
    }

    /**
     * 保存在发起登录的浏览器会话中；兑换授权码前先原子消费 state，防止重放。
     *
     * @since 2.0
     */
    public static final class Authorization {
        public final String url, state, verifier, redirectUri;

        Authorization(String url, String state, String verifier, String redirectUri) {
            this.url = url;
            this.state = state;
            this.verifier = verifier;
            this.redirectUri = redirectUri;
        }
    }
}
