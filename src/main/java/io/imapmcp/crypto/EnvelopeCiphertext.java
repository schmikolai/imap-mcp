package io.imapmcp.crypto;

/**
 * Wire/storage format for an envelope-encrypted secret. Maps 1:1 to the
 * {@code encrypted_secret}, {@code wrapped_dek}, {@code kms_key_id} and
 * {@code key_version} columns on {@code imap_account}.
 *
 * @param packedCiphertext nonce||ciphertext||tag, as produced by the local AEAD primitive
 * @param wrappedDek       the data encryption key, wrapped (encrypted) by the KMS master key
 * @param kmsKeyId         identifier of the KMS master key used to wrap {@code wrappedDek}
 * @param keyVersion       crypto-agility marker; bumped when a record is re-encrypted under a new DEK
 */
public record EnvelopeCiphertext(
        byte[] packedCiphertext,
        byte[] wrappedDek,
        String kmsKeyId,
        int keyVersion) {
}
