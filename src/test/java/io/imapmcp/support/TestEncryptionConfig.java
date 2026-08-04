package io.imapmcp.support;

import io.imapmcp.crypto.EncryptionService;
import io.imapmcp.crypto.LocalDevEncryptionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Reuses {@link LocalDevEncryptionService} (normally gated behind the
 * "local" Spring profile) as an explicit, always-active bean for tests, so
 * integration tests never need a real OpenBao instance. {@code @Primary}
 * wins over {@code OpenBaoEnvelopeEncryptionService}, which still gets
 * component-scanned in the test context since tests don't activate the
 * "local" profile.
 */
@TestConfiguration
public class TestEncryptionConfig {

    @Bean
    @Primary
    public EncryptionService encryptionService() {
        return new LocalDevEncryptionService();
    }
}
