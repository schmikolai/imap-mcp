package io.imapmcp.config;

import io.imapmcp.mcp.JwtMcpAuthenticationConverter;
import io.imapmcp.tenant.TenantContextFilter;
import io.imapmcp.tenant.TenantUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

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
                                                       TenantUserRepository tenantUserRepository) throws Exception {
        http
                .securityMatcher("/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtMcpAuthenticationConverter)))
                .addFilterAfter(new TenantContextFilter(tenantUserRepository), AuthorizationFilter.class);

        return http.build();
    }
}
