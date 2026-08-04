package io.imapmcp.crypto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link OpenBaoEnvelopeEncryptionService} against a real OpenBao
 * server running in dev mode (in-memory storage, auto-unsealed — fine for a
 * throwaway test container; never used this way for the actual home-server
 * deployment, which needs durable storage, see docker-compose.yml). This
 * genuinely verifies the HTTP/JSON contract against the real Transit API,
 * which mocking the RestClient in {@link OpenBaoEnvelopeEncryptionServiceTest}
 * can't catch on its own.
 */
class OpenBaoEnvelopeEncryptionServiceIntegrationTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String TRANSIT_KEY = "imap-mcp-test-key";

    private static GenericContainer<?> openbao;
    private static OpenBaoEnvelopeEncryptionService service;

    @BeforeAll
    static void startOpenBaoAndCreateTransitKey() {
        openbao = new GenericContainer<>(DockerImageName.parse("openbao/openbao:2.1.0"))
                .withExposedPorts(8200)
                .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
                .withCommand("server", "-dev")
                .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));
        openbao.start();

        String address = "http://" + openbao.getHost() + ":" + openbao.getMappedPort(8200);
        RestClient adminClient = RestClient.builder()
                .baseUrl(address)
                .defaultHeader("X-Vault-Token", ROOT_TOKEN)
                .build();

        adminClient.post().uri("/v1/sys/mounts/transit")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "transit"))
                .retrieve()
                .toBodilessEntity();
        adminClient.post().uri("/v1/transit/keys/{key}", TRANSIT_KEY)
                .retrieve()
                .toBodilessEntity();

        CryptoProperties properties = new CryptoProperties();
        properties.setOpenbaoAddress(address);
        properties.setOpenbaoToken(ROOT_TOKEN);
        properties.setTransitKeyName(TRANSIT_KEY);
        service = new OpenBaoEnvelopeEncryptionService(adminClient, properties);
    }

    @AfterAll
    static void stopOpenBao() {
        openbao.stop();
    }

    @Test
    void encryptThenDecryptRoundTripsAgainstRealOpenBao() {
        byte[] plaintext = "s3cret-app-password".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "imap_account|tenant-1|account-1".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext ciphertext = service.encrypt(plaintext, aad);

        try (SecureImapPassword decrypted = service.decrypt(ciphertext, aad)) {
            assertThat(decrypted.asTransientString()).isEqualTo("s3cret-app-password");
        }
    }

    @Test
    void decryptFailsWhenAssociatedDataDoesNotMatchTheRecordItWasEncryptedFor() {
        byte[] plaintext = "s3cret-app-password".getBytes(StandardCharsets.UTF_8);
        byte[] originalAad = "imap_account|tenant-1|account-1".getBytes(StandardCharsets.UTF_8);
        byte[] wrongAad = "imap_account|tenant-2|account-1".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext ciphertext = service.encrypt(plaintext, originalAad);

        assertThatThrownBy(() -> service.decrypt(ciphertext, wrongAad))
                .isInstanceOf(EncryptionException.class);
    }
}
