package io.imapmcp.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/** Not needed under the "local" profile — see {@link LocalDevEncryptionService}. */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
@Profile("!local")
public class OpenBaoClientConfig {

    // Lazy: building the underlying JDK HttpClient eagerly caches the JVM's
    // default SSLContext, which broke ImapMailServiceIntegrationTest's own
    // throwaway-cert trust store setup (that test's @BeforeAll sets
    // javax.net.ssl.trustStore, but only in time if nothing else forces the
    // default SSLContext to initialize first). Deferring construction until
    // the first real encrypt()/decrypt() call avoids that in tests that
    // never actually exercise this bean (see TestEncryptionConfig).
    @Bean
    @Lazy
    public RestClient openBaoRestClient(CryptoProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getOpenbaoAddress())
                .defaultHeader("X-Vault-Token", properties.getOpenbaoToken())
                .build();
    }
}
