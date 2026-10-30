package org.jetlinks.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetlinks.sdk.client.crypto.Signatures;

import reactor.core.publisher.Mono;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAKey;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 以模板 RSA 私钥取得实例凭据，独立于通知与已有 AccessSecret。 实例长期复用解析后的私钥及受限 HTTP 传输；调用方负责 close，不自动重试或跟随重定向。
 * 响应身份校验及解密后交给已有 OAuth2 或本地 Token 客户端，不自行保存凭据或管理轮换。
 *
 * @since 2.0
 * @see JetLinksReactiveClient
 */
public final class BusinessApplicationCredentials implements AutoCloseable {
    private static final String VERSION = "1";
    private static final String USAGE = "credential.read";
    private static final String REQUEST_DOMAIN = "business-application-credential/request";
    private static final int MIN_RSA_KEY_BITS = 2048;
    private static final int MAX_ID_LENGTH = 64;
    private static final int NONCE_BYTES = 16;
    private static final Pattern PLATFORM_ID = Pattern.compile("[!-~]{0,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JetLinksReactiveClient transport;
    private final PrivateKey privateKey;
    private final String keyId;

    public BusinessApplicationCredentials(String baseUrl, String keyId, String privatePem) {
        if (keyId == null || keyId.isBlank()) throw invalid();
        this.keyId = keyId;
        this.privateKey = Signatures.privateKey(privatePem);
        if (!(privateKey instanceof RSAKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_KEY_BITS)
            throw invalid();
        this.transport = new JetLinksReactiveClient(baseUrl, TokenProvider.anonymous());
    }

    /** platformId 留空时按模板 ID 签名；每次订阅生成新时间和随机 nonce，不自动重试。 */
    public Mono<Credential> read(String templateId, String applicationId, String platformId) {
        return Mono.defer(() -> requestCredential(templateId, applicationId, platformId));
    }

    private Mono<Credential> requestCredential(
            String templateId, String applicationId, String platformId) {
        if (!validId(templateId)
                || !validId(applicationId)
                || platformId == null
                || !PLATFORM_ID.matcher(platformId).matches()) throw invalid();
        String effective = platformId.isEmpty() ? templateId : platformId;
        byte[] random = new byte[NONCE_BYTES];
        RANDOM.nextBytes(random);
        String nonce = Signatures.base64(random);
        long timestamp = System.currentTimeMillis();
        String sign =
                Signatures.signPss(
                        privateKey,
                        REQUEST_DOMAIN,
                        "POST",
                        VERSION,
                        templateId,
                        applicationId,
                        effective,
                        USAGE,
                        nonce,
                        Long.toString(timestamp));
        var body =
                Map.ofEntries(
                        Map.entry("version", VERSION),
                        Map.entry("platformId", platformId),
                        Map.entry("usage", USAGE),
                        Map.entry("nonce", nonce),
                        Map.entry("timestamp", timestamp),
                        Map.entry("sign", sign));
        String path =
                "/business-application-template/"
                        + ApiRequest.segment(templateId)
                        + "/"
                        + ApiRequest.segment(applicationId)
                        + "/credential/_encrypted";
        var expected =
                Map.ofEntries(
                        Map.entry("version", VERSION),
                        Map.entry("platformId", effective),
                        Map.entry("templateId", templateId),
                        Map.entry("applicationId", applicationId),
                        Map.entry("usage", USAGE),
                        Map.entry("nonce", nonce),
                        Map.entry("keyId", keyId));
        return transport
                .request(ApiRequest.post(path, body).raw())
                .map(json -> decode(json, expected));
    }

    /** 身份与版本先于解密校验，避免将其他请求或模板的响应作为当前凭据。 */
    private Credential decode(JsonNode json, Map<String, String> expected) {
        var value = json.has("result") ? json.get("result") : json;
        if (value == null || !value.isObject()) throw responseInvalid();
        for (var entry : expected.entrySet()) {
            if (!value.path(entry.getKey()).isTextual()
                    || !entry.getValue().equals(value.path(entry.getKey()).textValue()))
                throw responseInvalid();
        }
        if (!value.path("keyRevision").isIntegralNumber()
                || !value.path("keyRevision").canConvertToLong()
                || value.path("keyRevision").longValue() < 1
                || !value.path("accessKey").isTextual()
                || value.path("accessKey").textValue().isBlank()
                || !value.path("encryptedAccessSecret").isTextual()) throw responseInvalid();
        String secret =
                Signatures.decrypt(value.path("encryptedAccessSecret").textValue(), privateKey);
        if (secret.isBlank()) throw responseInvalid();
        return new Credential(
                value.path("accessKey").textValue(),
                secret,
                keyId,
                value.path("keyRevision").longValue());
    }

    private static boolean validId(String value) {
        return value != null
                && !value.isBlank()
                && value.equals(value.trim())
                && value.length() <= MAX_ID_LENGTH;
    }

    private static SdkException invalid() {
        return new SdkException(
                SdkException.Code.INVALID_ARGUMENT, "Invalid credential request identity");
    }

    private static SdkException responseInvalid() {
        return new SdkException(
                SdkException.Code.AUTHENTICATION, "Invalid credential response identity");
    }

    @Override
    public void close() {
        transport.close();
    }

    /** 已核对请求身份并解密的当前凭据；仅由调用方保管，默认字符串表示隐藏密钥。 */
    public record Credential(
            String accessKey, String accessSecret, String keyId, long keyRevision) {
        @Override
        public String toString() {
            return "Credential[credential=[REDACTED]]";
        }
    }
}
