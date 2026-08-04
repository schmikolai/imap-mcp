package io.imapmcp.crypto;

import com.google.crypto.tink.subtle.AesGcmJce;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Envelope encryption backed by a self-hosted OpenBao (Vault-API-compatible)
 * Transit secrets engine: a fresh AES-256 data encryption key (DEK) is
 * requested per record via {@code transit/datakey/plaintext}, used once via
 * Tink's {@link AesGcmJce} to encrypt the secret, then discarded. Only the
 * OpenBao-wrapped form of the DEK (a {@code vault:v1:...} string) is
 * persisted; the plaintext DEK never touches storage or logs.
 *
 * <p>Swap this class for a different {@link EncryptionService} binding
 * without touching any caller — nothing outside this package depends on
 * OpenBao or Tink directly.
 *
 * <p>Disabled under the "local" profile in favor of
 * {@link LocalDevEncryptionService}, so local development never requires a
 * running OpenBao instance.
 */
@Service
@Profile("!local")
public class OpenBaoEnvelopeEncryptionService implements EncryptionService {

    static final int CURRENT_KEY_VERSION = 1;

    private final RestClient restClient;
    private final CryptoProperties properties;

    public OpenBaoEnvelopeEncryptionService(RestClient openBaoRestClient, CryptoProperties properties) {
        this.restClient = openBaoRestClient;
        this.properties = properties;
    }

    @Override
    public EnvelopeCiphertext encrypt(byte[] plaintext, byte[] associatedData) {
        TransitResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/transit/datakey/plaintext/{key}", properties.getTransitKeyName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(TransitResponse.class);
        } catch (RestClientException e) {
            throw new EncryptionException("Failed to generate data key from OpenBao", e);
        }

        byte[] plaintextDek = Base64.getDecoder().decode(response.data().plaintext());
        try {
            AesGcmJce aead = new AesGcmJce(plaintextDek);
            byte[] packed = aead.encrypt(plaintext, associatedData);
            byte[] wrappedDek = response.data().ciphertext().getBytes(StandardCharsets.UTF_8);
            return new EnvelopeCiphertext(packed, wrappedDek, properties.getTransitKeyName(), CURRENT_KEY_VERSION);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt secret", e);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    @Override
    public SecureImapPassword decrypt(EnvelopeCiphertext ciphertext, byte[] associatedData) {
        String wrappedDek = new String(ciphertext.wrappedDek(), StandardCharsets.UTF_8);

        TransitResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/transit/decrypt/{key}", ciphertext.kmsKeyId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ciphertext", wrappedDek))
                    .retrieve()
                    .body(TransitResponse.class);
        } catch (RestClientException e) {
            throw new EncryptionException("Failed to unwrap data key from OpenBao", e);
        }

        byte[] plaintextDek = Base64.getDecoder().decode(response.data().plaintext());
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

    private record TransitResponse(Data data) {
        private record Data(String plaintext, String ciphertext) {
        }
    }
}
