package io.imapmcp.mcp;

import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
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
 * Resolves the tenant + IMAP account an access token authorizes, and maps
 * its granted scopes to {@code SCOPE_*} authorities (delegating to the
 * standard {@link JwtGrantedAuthoritiesConverter} for that part).
 *
 * <p>The token's {@code sub} claim is the human's login email (principal
 * name at the time of the OAuth consent). <b>Known v1 simplification:</b>
 * this resolves to the tenant's <em>first</em> linked IMAP account rather
 * than a specific one chosen during consent — fine while most users link a
 * single account, but revisit once the consent screen lets a user pick
 * which account an agent is being granted access to (the data model already
 * has room for this via a per-grant {@code imap_account_id}).
 *
 * <p>Sets {@link TenantContext} itself, immediately after resolving
 * {@code tenantUser} — not left to {@code TenantContextFilter}. This
 * converter's own lookup below queries {@code imap_account}, which is
 * RLS-protected; {@code TenantContextFilter} only runs once authentication
 * (this converter) has already completed, so waiting for it would mean this
 * very query runs with no tenant context set and gets zero rows back under
 * RLS's fail-closed default. {@code TenantContextFilter} still runs
 * afterward and remains responsible for clearing it at the end of the
 * request.
 */
@Component
public class JwtMcpAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final TenantUserRepository tenantUserRepository;
    private final ImapAccountRepository imapAccountRepository;
    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    public JwtMcpAuthenticationConverter(TenantUserRepository tenantUserRepository,
                                          ImapAccountRepository imapAccountRepository) {
        this.tenantUserRepository = tenantUserRepository;
        this.imapAccountRepository = imapAccountRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        TenantUser tenantUser = tenantUserRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> invalidToken("Token subject is not a known user"));

        TenantContext.set(tenantUser.getId());

        ImapAccount account = imapAccountRepository.findByTenantUserId(tenantUser.getId()).stream()
                .findFirst()
                .orElseThrow(() -> invalidToken("User has no linked IMAP account"));

        McpPrincipal principal = new McpPrincipal(tenantUser.getId(), account.getId());
        Collection<GrantedAuthority> authorities = scopesConverter.convert(jwt);
        return new McpAuthenticationToken(principal, jwt, authorities);
    }

    private OAuth2AuthenticationException invalidToken(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
