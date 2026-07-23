package io.imapmcp.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/** Not needed under the "local" profile — see {@link LocalDevEncryptionService}. */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
@Profile("!local")
public class KmsClientConfig {

    @Bean
    public KmsClient kmsClient(CryptoProperties properties) {
        var builder = KmsClient.builder();
        if (properties.getAwsRegion() != null && !properties.getAwsRegion().isBlank()) {
            builder.region(Region.of(properties.getAwsRegion()));
        }
        // Credentials resolved via the default AWS credential provider chain
        // (env vars, instance/task role, etc.) — never hard-coded here.
        return builder.build();
    }
}
