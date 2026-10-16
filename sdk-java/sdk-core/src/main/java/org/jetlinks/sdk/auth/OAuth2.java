package org.jetlinks.sdk.auth;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.crypto.Signatures;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * OAuth2 client for one configured server/credential. User grants are explicit and never cached or
 * retried. @since 2.0
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

    public Mono<OAuth2Token> machine(List<String> scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scopes != null) form.put("scope", String.join(" ", scopes));
        return token(form);
    }

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
                .switchIfEmpty(Mono.error(new IllegalStateException("Invalid OAuth2 token response")))
                .map(
                        n -> {
                            if (n.accessToken == null || n.expiresIn <= 0)
                                throw new IllegalStateException("Invalid OAuth2 token response");
                            return n;
                        });
    }

    public CachedTokenProvider machineTokens(List<String> scopes) {
        return new CachedTokenProvider(
                () ->
                        machine(scopes)
                                .map(
                                        n ->
                                                new CachedTokenProvider.Issued(
                                                        n.accessToken,
                                                        System.currentTimeMillis()
                                                                + n.expiresIn
                                                                        * 1000)),
                Duration.ofSeconds(10));
    }

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

    public Authorization authorization(String redirectUri, List<String> scopes) {
        String state = random(), verifier = random();
        String challenge;
        try {
            challenge =
                    Signatures.base64(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
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
     * Store this record in the initiating browser session and atomically consume state before
     * exchanging code.
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
