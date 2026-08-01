package io.imapmcp.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.imapmcp.auth.OAuthProperties;
import io.imapmcp.mcp.JwtMcpAuthenticationConverter;
import io.imapmcp.ratelimit.RateLimitFilter;
import io.imapmcp.ratelimit.RateLimitProperties;
import io.imapmcp.tenant.TenantContext;
import io.imapmcp.tenant.TenantContextFilter;
import io.imapmcp.tenant.TenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.util.UUID;

/**
 * A separate filter chain for {@code /mcp/**}, authenticated as an OAuth2
 * resource server validating JWT access tokens issued by our own
 * Authorization Server ({@link AuthorizationServerConfig}) — distinct from
 * the first-party form-login chain in {@link SecurityConfig}; MCP clients
 * and the human web UI must never share a session/credential model. CSRF is
 * disabled here because this is a stateless bearer-token API, not
 * cookie/session-based; it stays enabled on the web chain.
 *
 * <p>Replaces the phase-3 static-bearer-token stopgap now that real OAuth is
 * in place — every {@code /mcp/**} request now carries a token scoped to a
 * specific tenant and a specific set of granted scopes (see
 * {@link JwtMcpAuthenticationConverter} and {@code ToolDispatcher}'s
 * per-tool scope checks), rather than one shared token granting full access.
 */
@Configuration
public class McpSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, JwtMcpAuthenticationConverter jwtMcpAuthenticationConverter,
                                                       TenantUserRepository tenantUserRepository,
                                                       OAuthProperties oAuthProperties,
                                                       ProxyManager<String> bucket4jProxyManager,
                                                       RateLimitProperties rateLimitProperties) throws Exception {
        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                key -> bucket4jProxyManager.builder().build("rl:mcp:" + key,
                        rateLimitProperties.getMcpToolCalls()::toBucketConfiguration),
                McpSecurityConfig::tenantOrRemoteAddr,
                null);

        http
                .securityMatcher("/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(protectedResourceMetadataEntryPoint(oAuthProperties))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtMcpAuthenticationConverter)))
                .addFilterAfter(new TenantContextFilter(tenantUserRepository), AuthorizationFilter.class)
                .addFilterAfter(rateLimitFilter, TenantContextFilter.class);

        return http.build();
    }

    /**
     * Keyed by tenant, not IP, so every agent acting for one tenant shares
     * one bucket regardless of source IP. {@link TenantContext} is already
     * populated by {@link TenantContextFilter}, which runs immediately
     * before this filter in the chain; the remote-address fallback is
     * defensive only — this chain requires {@code authenticated()}, so
     * tenant resolution should never actually fail here.
     */
    private static String tenantOrRemoteAddr(HttpServletRequest request) {
        UUID tenantId = TenantContext.get();
        return tenantId != null ? tenantId.toString() : request.getRemoteAddr();
    }

    /**
     * Wraps Spring's default bearer-token 401 handling to add a
     * {@code resource_metadata} hint pointing at
     * {@code /.well-known/oauth-protected-resource} (RFC 9728), so an MCP
     * client hitting {@code /mcp} with no token — and no prior knowledge of
     * this server at all — can discover the authorization server and the
     * {@code mcp:*} scopes to request, rather than guessing and requesting
     * none (see {@code ProtectedResourceMetadataController}).
     */
    private static AuthenticationEntryPoint protectedResourceMetadataEntryPoint(OAuthProperties properties) {
        BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
        String resourceMetadataUri = properties.getIssuerUri() + "/.well-known/oauth-protected-resource/mcp";
        return (request, response, authException) -> {
            delegate.commence(request, response, authException);
            String wwwAuthenticate = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
            if (wwwAuthenticate != null) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                        wwwAuthenticate + ", resource_metadata=\"" + resourceMetadataUri + "\"");
            }
        };
    }
}
