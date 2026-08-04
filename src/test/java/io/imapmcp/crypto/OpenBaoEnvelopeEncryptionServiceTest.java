package io.imapmcp.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the real envelope-encryption/AEAD logic with OpenBao itself
 * mocked out (no running OpenBao instance needed to run this test) —
 * datakey/plaintext and decrypt are stubbed to hand back a real random
 * AES-256 key, so the local AesGcmJce encrypt/decrypt path and AAD binding
 * are genuinely tested.
 */
class OpenBaoEnvelopeEncryptionServiceTest {

    private static final String OPENBAO_ADDRESS = "http://openbao.test:8200";
    private static final String TRANSIT_KEY = "test-key";
    private static final String WRAPPED_DEK = "vault:v1:fake-wrapped-dek";

    private MockRestServiceServer mockServer;
    private OpenBaoEnvelopeEncryptionService service;
    private byte[] dekBytes;

    private void setUp() {
        dekBytes = new byte[32];
        new SecureRandom().nextBytes(dekBytes);
        String dekBase64 = Base64.getEncoder().encodeToString(dekBytes);

        RestClient.Builder builder = RestClient.builder().baseUrl(OPENBAO_ADDRESS);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        mockServer.expect(requestTo(OPENBAO_ADDRESS + "/v1/transit/datakey/plaintext/" + TRANSIT_KEY))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"data": {"plaintext": "%s", "ciphertext": "%s"}}
                        """.formatted(dekBase64, WRAPPED_DEK), MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(OPENBAO_ADDRESS + "/v1/transit/decrypt/" + TRANSIT_KEY))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"data": {"plaintext": "%s"}}
                        """.formatted(dekBase64), MediaType.APPLICATION_JSON));

        CryptoProperties properties = new CryptoProperties();
        properties.setOpenbaoAddress(OPENBAO_ADDRESS);
        properties.setTransitKeyName(TRANSIT_KEY);
        service = new OpenBaoEnvelopeEncryptionService(restClient, properties);
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
        mockServer.verify();
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
