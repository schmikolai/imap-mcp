package io.imapmcp.crypto;

import com.google.crypto.tink.subtle.AesGcmJce;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Local-development stand-in for {@link AwsKmsEnvelopeEncryptionService},
 * active only under the "local" Spring profile so the app can run end to
 * end (docker-compose up + ./gradlew bootRun --args='--spring.profiles.active=local')
 * without an AWS account. Uses one AES-256 key generated fresh in memory at
 * process startup — there is no KMS-backed master key at all.
 *
 * <p><b>Never use this outside local development.</b> Every secret encrypted
 * under this profile becomes permanently unreadable the moment the process
 * restarts (the key is gone), and there is no envelope protection — anyone
 * with heap access has the key. It exists purely to remove the AWS
 * dependency from the local dev loop.
 */
@Service
@Profile("local")
public class LocalDevEncryptionService implements EncryptionService {

    private static final String LOCAL_KEY_ID = "local-dev-key-not-kms-backed";

    private final byte[] processLocalKey;

    public LocalDevEncryptionService() {
        processLocalKey = new byte[32];
        new SecureRandom().nextBytes(processLocalKey);
    }

    @Override
    public EnvelopeCiphertext encrypt(byte[] plaintext, byte[] associatedData) {
        try {
            AesGcmJce aead = new AesGcmJce(processLocalKey);
            byte[] packed = aead.encrypt(plaintext, associatedData);
            return new EnvelopeCiphertext(packed, processLocalKey.clone(), LOCAL_KEY_ID, 1);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("local-dev encryption failed", e);
        }
    }

    @Override
    public SecureImapPassword decrypt(EnvelopeCiphertext ciphertext, byte[] associatedData) {
        try {
            AesGcmJce aead = new AesGcmJce(processLocalKey);
            byte[] plaintext = aead.decrypt(ciphertext.packedCiphertext(), associatedData);
            return new SecureImapPassword(plaintext);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("local-dev decryption failed", e);
        }
    }
}
