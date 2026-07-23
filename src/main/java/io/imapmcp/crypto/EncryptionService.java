package io.imapmcp.crypto;

/**
 * Envelope-encryption boundary for secrets that must be stored durably (IMAP
 * passwords). Implementations must never return or accept a bare data
 * encryption key — callers only ever see {@link EnvelopeCiphertext}.
 *
 * {@code associatedData} binds a ciphertext to the record it belongs to
 * (e.g. tenantId + imapAccountId) so a ciphertext copied into a different
 * row fails to decrypt instead of silently decrypting as if it belonged
 * there.
 */
public interface EncryptionService {

    EnvelopeCiphertext encrypt(byte[] plaintext, byte[] associatedData);

    /**
     * Decrypts into a {@link SecureImapPassword}. Callers must call
     * {@link SecureImapPassword#destroy()} as soon as the secret is no
     * longer needed.
     */
    SecureImapPassword decrypt(EnvelopeCiphertext ciphertext, byte[] associatedData);
}
