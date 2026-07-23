package io.imapmcp.crypto;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real envelope-encryption/AEAD logic with KMS itself mocked
 * out (no AWS credentials needed to run this test) — GenerateDataKey and
 * Decrypt are stubbed to hand back a real random AES-256 key, so the local
 * AesGcmJce encrypt/decrypt path and AAD binding are genuinely tested.
 */
class AwsKmsEnvelopeEncryptionServiceTest {

    private static final byte[] FAKE_WRAPPED_DEK = "fake-wrapped-dek".getBytes(StandardCharsets.UTF_8);

    private KmsClient kmsClient;
    private AwsKmsEnvelopeEncryptionService service;
    private byte[] dekBytes;

    private void setUp() {
        dekBytes = new byte[32];
        new SecureRandom().nextBytes(dekBytes);

        kmsClient = mock(KmsClient.class);
        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class))).thenReturn(GenerateDataKeyResponse.builder()
                .plaintext(SdkBytes.fromByteArray(dekBytes))
                .ciphertextBlob(SdkBytes.fromByteArray(FAKE_WRAPPED_DEK))
                .build());
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromByteArray(dekBytes))
                .build());

        CryptoProperties properties = new CryptoProperties();
        properties.setKmsKeyId("arn:aws:kms:eu-central-1:000000000000:key/test-key");
        service = new AwsKmsEnvelopeEncryptionService(kmsClient, properties);
    }

    @Test
    void encryptThenDecryptRoundTripsThePlaintext() {
        setUp();
        byte[] plaintext = "s3cret-app-password".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "imap_account|tenant-1|account-1".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext ciphertext = service.encrypt(plaintext, aad);

        try (SecureImapPassword decrypted = service.decrypt(ciphertext, aad)) {
            assertThat(decrypted.asTransientString()).isEqualTo("s3cret-app-password");
        }
    }

    @Test
    void decryptFailsWhenAssociatedDataDoesNotMatchTheRecordItWasEncryptedFor() {
        setUp();
        byte[] plaintext = "s3cret-app-password".getBytes(StandardCharsets.UTF_8);
        byte[] originalAad = "imap_account|tenant-1|account-1".getBytes(StandardCharsets.UTF_8);
        byte[] wrongAad = "imap_account|tenant-2|account-1".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext ciphertext = service.encrypt(plaintext, originalAad);

        assertThatThrownBy(() -> service.decrypt(ciphertext, wrongAad))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void secureImapPasswordIsZeroedAfterDestroy() {
        setUp();
        byte[] plaintext = "s3cret-app-password".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "imap_account|tenant-1|account-1".getBytes(StandardCharsets.UTF_8);
        EnvelopeCiphertext ciphertext = service.encrypt(plaintext, aad);

        SecureImapPassword decrypted = service.decrypt(ciphertext, aad);
        decrypted.destroy();

        assertThatThrownBy(decrypted::asTransientString).isInstanceOf(IllegalStateException.class);
    }
}
