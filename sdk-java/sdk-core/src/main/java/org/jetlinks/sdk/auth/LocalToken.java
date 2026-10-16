package org.jetlinks.sdk.auth;

import org.jetlinks.sdk.crypto.Signatures;

import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binary application self-signed machine Token; null scopes preserves platform default, empty means
 * zero scope. @since 2.0
 */
public final class LocalToken {
    private LocalToken() {}

    public static String create(String key, String secret, List<String> scopes, long issuedAt) {
        if (key == null || key.isEmpty() || secret == null || secret.isEmpty() || issuedAt < 0)
            throw new IllegalArgumentException("Invalid credential");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(
                    ByteBuffer.allocate(9)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .put((byte) 11)
                            .putLong(issuedAt)
                            .array());
            out.writeByte(1);
            out.writeByte(2);
            string(out, key);
            out.writeByte(scopes == null ? 0 : 1);
            SortedSet<String> sorted = new TreeSet<>();
            if (scopes != null)
                for (String scope : scopes) {
                    if (scope == null || scope.trim().isEmpty())
                        throw new IllegalArgumentException("Invalid scope");
                    sorted.add(scope.trim());
                }
            if (sorted.size() > 65535) throw new IllegalArgumentException("Too many scopes");
            out.writeShort(sorted.size());
            for (String s : sorted) string(out, s);
            string(out, "");
            byte[] derived =
                    Signatures.hmac(
                            secret.getBytes(StandardCharsets.UTF_8),
                            "application-access/self-signed/v1"
                                    .getBytes(StandardCharsets.US_ASCII));
            byte[] signature = Signatures.hmac(derived, bytes.toByteArray());
            Arrays.fill(derived, (byte) 0);
            out.write(signature, 0, 16);
            String result = "at_" + base62(bytes.toByteArray());
            if (result.length() > 4096)
                throw new IllegalArgumentException("Token exceeds size limit");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        if (b.length > 1024) throw new IllegalArgumentException("Token field too long");
        out.writeShort(b.length);
        out.write(b);
    }

    private static String base62(byte[] bytes) {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        BigInteger n = new BigInteger(1, bytes), base = BigInteger.valueOf(62);
        StringBuilder s = new StringBuilder();
        do {
            BigInteger[] d = n.divideAndRemainder(base);
            s.append(alphabet.charAt(d[1].intValue()));
            n = d[0];
        } while (n.signum() > 0);
        for (int i = 0; i < bytes.length - 1 && bytes[i] == 0; i++) s.append('0');
        return s.reverse().toString();
    }
}
