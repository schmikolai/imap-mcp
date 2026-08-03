package io.imapmcp.mcp;

import io.imapmcp.tenant.TenantContext;
import io.imapmcp.tenant.TenantUser;
import io.imapmcp.tenant.TenantUserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Resolves the tenant an access token authorizes, and maps its granted
 * scopes to {@code SCOPE_*} authorities (delegating to the standard
 * {@link JwtGrantedAuthoritiesConverter} for that part).
 *
 * <p>The token's {@code sub} claim is the human's login email (principal
 * name at the time of the OAuth consent). A grant is tenant-wide, not tied
 * to one {@code ImapAccount} — it does not resolve or require any linked
 * account here; {@link ToolDispatcher} resolves the account per tool call
 * instead (see {@link McpPrincipal}), which is what lets a grant made before
 * a second account is linked keep working afterward.
 *
 * <p>Sets {@link TenantContext} itself, immediately after resolving
 * {@code tenantUser}, rather than leaving it to {@code TenantContextFilter}
 * (which only runs later, once authentication has completed) — kept even
 * though this converter no longer queries any RLS-protected table itself,
 * so a future addition here doesn't reintroduce the fail-closed
 * zero-rows-under-no-tenant-context trap. {@code TenantContextFilter} still
 * runs afterward and remains responsible for clearing it at the end of the
 * request.
 */
@Component
public class JwtMcpAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final TenantUserRepository tenantUserRepository;
    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    public JwtMcpAuthenticationConverter(TenantUserRepository tenantUserRepository) {
        this.tenantUserRepository = tenantUserRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        TenantUser tenantUser = tenantUserRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> invalidToken("Token subject is not a known user"));

        TenantContext.set(tenantUser.getId());

        McpPrincipal principal = new McpPrincipal(tenantUser.getId());
        Collection<GrantedAuthority> authorities = scopesConverter.convert(jwt);
        return new McpAuthenticationToken(principal, jwt, authorities);
    }

    private OAuth2AuthenticationException invalidToken(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
