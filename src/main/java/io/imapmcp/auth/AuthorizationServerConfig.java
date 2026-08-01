package io.imapmcp.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.imapmcp.mcp.McpScopes;
import io.imapmcp.ratelimit.RateLimitFilter;
import io.imapmcp.ratelimit.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The OAuth 2.1 Authorization Server: issues scoped, short-lived JWT access
 * tokens (+ rotating refresh tokens) to vetted agent clients via
 * authorization-code + mandatory PKCE. This is the boundary an AI agent
 * authenticates through — never the human's IMAP password, which this
 * layer never sees.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class AuthorizationServerConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                                                                        ProxyManager<String> bucket4jProxyManager,
                                                                        RateLimitProperties rateLimitProperties) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        RequestMatcher rateLimitedEndpoints = new OrRequestMatcher(
                new AntPathRequestMatcher("/oauth2/token", HttpMethod.POST.name()),
                new AntPathRequestMatcher("/oauth2/authorize"));
        // Per client IP, not per authenticated principal — /oauth2/authorize
        // has no principal yet on first hit, and this is meant to slow down
        // credential-stuffing/auth-code-guessing before authentication ever
        // happens. See RateLimitFilter's javadoc on the X-Forwarded-For
        // caveat if a reverse proxy is ever added in front of this app.
        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                key -> bucket4jProxyManager.builder().build("rl:oauth:" + key,
                        rateLimitProperties.getOauthEndpoints()::toBucketConfiguration),
                HttpServletRequest::getRemoteAddr,
                rateLimitedEndpoints);

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer ->
                        authorizationServer
                                .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth2/consent"))
                                // Without scopes_supported here, an MCP client
                                // doing AS-metadata-only discovery (no
                                // resource_metadata hint yet followed) has no
                                // way to learn the mcp:* scopes and ends up
                                // sending /oauth2/authorize with no scope
                                // param at all — which our consent page then
                                // renders with nothing to approve, and Spring
                                // Authorization Server treats that as an
                                // outright deny (misreported as
                                // access_denied/"client_id").
                                .authorizationServerMetadataEndpoint(endpoint -> endpoint
                                        .authorizationServerMetadataCustomizer(builder -> builder
                                                .scope(McpScopes.MAIL_READ)
                                                .scope(McpScopes.MAIL_WRITE)
                                                .scope(McpScopes.MAILBOX_MANAGE)
                                                .scope(McpScopes.MAIL_DELETE))))
                // /oauth2/authorize (+ our /oauth2/consent) is the one
                // genuinely browser/session-driven endpoint here and keeps
                // CSRF protection (satisfied via the custom consent page's
                // Thymeleaf-rendered token). Everything else — token,
                // introspection, revocation — is client-credential
                // authenticated (Basic/POST secret), not cookie-based, so
                // CSRF protection there is a false-positive block, not a
                // real defense.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/token", "/oauth2/introspect", "/oauth2/revoke"))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                             RegisteredClientRepository registeredClientRepository) {
        return new HashedTokenOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(OAuthProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuerUri())
                .build();
    }

    /**
     * Binds every access token to this server as audience (RFC 8707-style
     * resource indication) — defense-in-depth against a token being replayed
     * against some other resource server, even though today there's only
     * this one.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(OAuthProperties properties) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // A plain ArrayList, not List.of(...): the claims here get
                // embedded in the authorization's persisted metadata and
                // read back (e.g. on refresh), and Spring Security's Jackson
                // deserialization allowlist doesn't recognize the JDK-internal
                // ImmutableCollections$List12 that List.of(...) returns —
                // that 500s the very next refresh-token exchange.
                List<String> audience = new ArrayList<>();
                audience.add(properties.getIssuerUri());
                context.getClaims().audience(audience);
            }
        };
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = generateRsaKey();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    private static RSAKey generateRsaKey() {
        KeyPair keyPair = generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        }
    }
}
