package io.imapmcp.crypto;

import com.google.crypto.tink.subtle.AesGcmJce;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Envelope encryption backed by AWS KMS: a fresh AES-256 data encryption key
 * (DEK) is requested from KMS per record via {@code GenerateDataKey}, used
 * once via Tink's {@link AesGcmJce} to encrypt the secret, then discarded.
 * Only the KMS-wrapped form of the DEK is persisted; the plaintext DEK never
 * touches storage or logs.
 *
 * <p>Swap this class for a different {@link EncryptionService} binding
 * (GCP KMS, Vault Transit) without touching any caller — nothing outside
 * this package depends on AWS KMS or Tink directly.
 *
 * <p>Disabled under the "local" profile in favor of
 * {@link LocalDevEncryptionService}, so local development never requires an
 * AWS account.
 */
@Service
@Profile("!local")
public class AwsKmsEnvelopeEncryptionService implements EncryptionService {

    private static final DataKeySpec DEK_SPEC = DataKeySpec.AES_256;
    static final int CURRENT_KEY_VERSION = 1;

    private final KmsClient kmsClient;
    private final CryptoProperties properties;

    public AwsKmsEnvelopeEncryptionService(KmsClient kmsClient, CryptoProperties properties) {
        this.kmsClient = kmsClient;
        this.properties = properties;
    }

    @Override
    public EnvelopeCiphertext encrypt(byte[] plaintext, byte[] associatedData) {
        GenerateDataKeyResponse dataKey = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(properties.getKmsKeyId())
                .keySpec(DEK_SPEC)
                .build());

        byte[] plaintextDek = dataKey.plaintext().asByteArray();
        try {
            AesGcmJce aead = new AesGcmJce(plaintextDek);
            byte[] packed = aead.encrypt(plaintext, associatedData);
            byte[] wrappedDek = dataKey.ciphertextBlob().asByteArray();
            return new EnvelopeCiphertext(packed, wrappedDek, properties.getKmsKeyId(), CURRENT_KEY_VERSION);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt secret", e);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    @Override
    public SecureImapPassword decrypt(EnvelopeCiphertext ciphertext, byte[] associatedData) {
        SdkBytes wrappedDek = SdkBytes.fromByteArray(ciphertext.wrappedDek());
        byte[] plaintextDek = kmsClient.decrypt(DecryptRequest.builder()
                        .keyId(ciphertext.kmsKeyId())
                        .ciphertextBlob(wrappedDek)
                        .build())
                .plaintext()
                .asByteArray();

        try {
            AesGcmJce aead = new AesGcmJce(plaintextDek);
            byte[] plaintext = aead.decrypt(ciphertext.packedCiphertext(), associatedData);
            return new SecureImapPassword(plaintext);
        } catch (GeneralSecurityException e) {
            // Covers AEAD authentication failure — e.g. associatedData mismatch,
            // meaning the ciphertext does not belong to the record it was read from.
            throw new EncryptionException("Failed to decrypt secret; ciphertext/AAD mismatch or corruption", e);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }
}
