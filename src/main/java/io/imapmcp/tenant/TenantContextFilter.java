package io.imapmcp.tenant;

import io.imapmcp.mcp.McpPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Populates {@link TenantContext} from whichever principal type is
 * currently authenticated — an {@link McpPrincipal} (OAuth-authenticated
 * agent) already carries the tenant id directly; a first-party web-session
 * {@link UserDetails} only carries the login email, so it's resolved via
 * {@link TenantUserRepository}. That lookup itself is safe to run before
 * the tenant context is set — {@code tenant_user} has no RLS policy, only
 * {@code imap_account} does.
 *
 * <p>Deliberately NOT a {@code @Component}: see the equivalent note on
 * {@code StaticBearerTokenAuthFilter}'s removal — any {@code Filter} bean
 * gets auto-registered globally by Spring Boot regardless of where it's
 * also wired via {@code addFilterAfter}. Constructed with {@code new}
 * inside each {@code SecurityFilterChain} bean method instead, and added
 * after {@code AuthorizationFilter} so {@code Authentication} is already
 * fully resolved by the time this runs.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantUserRepository tenantUserRepository;

    public TenantContextFilter(TenantUserRepository tenantUserRepository) {
        this.tenantUserRepository = tenantUserRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            resolveTenantId().ifPresent(TenantContext::set);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<UUID> resolveTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof McpPrincipal mcpPrincipal) {
            return Optional.of(mcpPrincipal.tenantUserId());
        }
        if (principal instanceof UserDetails userDetails) {
            return tenantUserRepository.findByEmail(userDetails.getUsername()).map(TenantUser::getId);
        }
        return Optional.empty();
    }
}
