package io.imapmcp.mcp;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

/**
 * Resolves an {@code @AuthenticationPrincipal McpPrincipal} directly for
 * MCP requests, instead of the raw {@link Jwt} that
 * {@code JwtAuthenticationToken} would otherwise expose as the principal —
 * see {@link JwtMcpAuthenticationConverter}.
 */
public class McpAuthenticationToken extends AbstractAuthenticationToken {

    private final McpPrincipal principal;
    private final Jwt jwt;

    public McpAuthenticationToken(McpPrincipal principal, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
