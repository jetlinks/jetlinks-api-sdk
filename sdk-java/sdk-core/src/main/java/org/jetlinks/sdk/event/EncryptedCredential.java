package org.jetlinks.sdk.event;

/** Encrypted application credential. keyId selects the receiver's private key. @since 2.0 */
public class EncryptedCredential {
    public String accessKey, encryptedAccessSecret, keyId;
    public Long keyRevision;
}
