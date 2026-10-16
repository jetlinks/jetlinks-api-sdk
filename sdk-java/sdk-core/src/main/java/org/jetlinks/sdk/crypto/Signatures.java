package org.jetlinks.sdk.crypto;

import org.jetlinks.sdk.SdkException;
import org.jetlinks.sdk.SdkException.Code;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * 通知和命令共用的 v1 签名及凭据加解密原语。
 *
 * @since 2.0
 */
public final class Signatures {
    private Signatures() {}

    public static byte[] hmac(byte[] secret, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot compute credential signature", e);
        }
    }

    /** 以 UTF-8 字节长度前缀和大端整数拼接字段，避免分隔符或字符长度造成歧义。 */
    public static byte[] lp(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String field : fields) {
                byte[] b = field.getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot compute credential signature", e);
        }
    }

    public static String sign(String secret, String... fields) {
        return base64(hmac(secret.getBytes(StandardCharsets.UTF_8), lp(fields)));
    }

    public static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot compute credential signature", e);
        }
    }

    public static boolean matches(String expected, String actual) {
        return actual != null
                && actual.matches("[A-Za-z0-9_-]{43}")
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
    }

    public static String decrypt(String ciphertext, String privatePem) {
        try {
            byte[] der =
                    Base64.getMimeDecoder()
                            .decode(
                                    privatePem
                                            .replace("-----BEGIN PRIVATE KEY-----", "")
                                            .replace("-----END PRIVATE KEY-----", ""));
            PrivateKey key =
                    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            // 显式指定 MGF1 的 SHA-256，避免部分 JDK 在 OAEP SHA-256 下仍默认使用 SHA-1。
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new OAEPParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            PSource.PSpecified.DEFAULT));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SdkException(Code.CRYPTOGRAPHY, "Cannot decrypt application credential", e);
        }
    }
}
