package org.jetlinks.sdk.client.event;

/**
 * 加密应用凭据；keyId 用于选择接收方的私钥。
 *
 * @since 2.0
 */
public class EncryptedCredential {
    public String accessKey, encryptedAccessSecret, keyId;
    public Long keyRevision;
}
