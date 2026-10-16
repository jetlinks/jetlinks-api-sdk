package org.jetlinks.sdk.crypto;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Current v1 protocol primitives shared by notifications and commands. No legacy fallback. @since
 * 2.0
 */
public final class Signatures {
    private Signatures() {}

    public static byte[] hmac(byte[] secret, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

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
            throw new IllegalStateException(e);
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
            throw new IllegalStateException(e);
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
            // JDK providers otherwise commonly default MGF1 to SHA-1 despite OAEP SHA-256.
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
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Cannot decrypt application credential", e);
        }
    }
}
