package org.jetlinks.sdk.client;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.util.unit.DataSize;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;

@Getter
@Setter
public class ClientConfig {

    private String baseUrl;

    private String clientId;

    private String secureKey;

    private Signature signature = Signature.MD5;

    private boolean tokenMod;

    private Duration requestTimeout = Duration.ofMinutes(1);

    private DataSize maxBodySize = DataSize.ofMegabytes(64);

    public ClientConfig(String baseUrl,
                        String clientId,
                        String secureKey) {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(secureKey);
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.secureKey = secureKey;
    }

    public enum Signature {
        MD5 {
            @Override
            public String sign(byte[] payload) {
                return DigestUtils.md5Hex(payload);
            }

            @Override
            public MessageDigest getMessageDigest() {
                return DigestUtils.getMd5Digest();
            }
        },
        SHA256 {
            @Override
            public String sign(byte[] payload) {
                return DigestUtils.sha256Hex(payload);
            }

            @Override
            public MessageDigest getMessageDigest() {
                return DigestUtils.getSha256Digest();
            }
        };

        public abstract MessageDigest getMessageDigest();

        public abstract String sign(byte[] payload);
    }


}
