package io.imapmcp.auth;

import io.imapmcp.mcp.McpScopes;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotently seeds one statically-configured OAuth client on startup —
 * the "vetted allow-list, no open Dynamic Client Registration yet" model
 * from plan phase 4. No-op if {@code imapmcp.oauth.seed-client.client-id}
 * isn't set. Intended for the first known agent vendor; add more the same
 * way (or directly in {@code oauth2_registered_client}) as they're vetted.
 */
@Component
public class OAuthClientSeeder implements ApplicationRunner {

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuthProperties properties;
    private final PasswordEncoder passwordEncoder;

    public OAuthClientSeeder(RegisteredClientRepository registeredClientRepository,
                              OAuthProperties properties,
                              PasswordEncoder passwordEncoder) {
        this.registeredClientRepository = registeredClientRepository;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        OAuthProperties.SeedClient seed = properties.getSeedClient();
        if (seed.getClientId() == null || seed.getClientId().isBlank()) {
            return;
        }
        if (registeredClientRepository.findByClientId(seed.getClientId()) != null) {
            return;
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(seed.getClientId())
                .clientName(seed.getDisplayName())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(McpScopes.MAIL_READ)
                .scope(McpScopes.MAIL_WRITE)
                .scope(McpScopes.MAILBOX_MANAGE)
                .scope(McpScopes.MAIL_DELETE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build());

        seed.getRedirectUris().forEach(builder::redirectUri);

        if (seed.getClientSecret() != null && !seed.getClientSecret().isBlank()) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientSecret(passwordEncoder.encode(seed.getClientSecret()));
        } else {
            // Public client (no secret) — still requires PKCE above.
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }

        registeredClientRepository.save(builder.build());
    }
}
