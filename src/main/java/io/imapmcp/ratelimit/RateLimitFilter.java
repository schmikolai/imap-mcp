package io.imapmcp.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Function;

/**
 * Applies one bucket4j bucket per request, keyed however the caller likes
 * (per tenant, per IP, ...). One instance of this is constructed per
 * rate-limited surface — see {@code McpSecurityConfig}, {@code
 * AuthorizationServerConfig} and {@code SecurityConfig} for the three
 * current uses, each with its own key extractor, bucket limits, and scope.
 *
 * <p>Deliberately NOT a {@code @Component} — see {@code TenantContextFilter}'s
 * javadoc for why: any {@code Filter} bean gets auto-registered globally by
 * Spring Boot regardless of where it's also wired via {@code
 * addFilterAfter}/{@code addFilterBefore}. Constructed with {@code new}
 * inside each {@code SecurityFilterChain} bean method instead.
 *
 * <p>Takes a plain {@code Function<String, Bucket>} rather than a {@code
 * ProxyManager} directly, so it stays Redis-agnostic and can be unit
 * tested against local (non-distributed) buckets.
 *
 * <p>Callers that key by IP use {@code HttpServletRequest.getRemoteAddr()}
 * directly, with no {@code X-Forwarded-For} trust configured. That's fine
 * for a single-instance deployment with no reverse proxy in front; if one
 * is ever added, every request would resolve to the proxy's IP and share
 * one bucket across all real clients unless a trusted-proxy setup (e.g.
 * Spring's {@code ForwardedHeaderFilter}) is added at the same time.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Function<String, Bucket> bucketResolver;
    private final Function<HttpServletRequest, String> keyExtractor;
    private final RequestMatcher scopeMatcher;

    /**
     * @param scopeMatcher if non-null, only requests matching it are
     *                     rate-limited; everything else passes through
     *                     untouched. Pass {@code null} when the whole
     *                     filter chain is already scoped to what should be
     *                     limited (e.g. a chain matched to {@code /mcp/**}).
     */
    public RateLimitFilter(Function<String, Bucket> bucketResolver,
                            Function<HttpServletRequest, String> keyExtractor,
                            RequestMatcher scopeMatcher) {
        this.bucketResolver = bucketResolver;
        this.keyExtractor = keyExtractor;
        this.scopeMatcher = scopeMatcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (scopeMatcher != null && !scopeMatcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = keyExtractor.apply(request);
        Bucket bucket = bucketResolver.apply(key);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        response.setStatus(429); // HTTP 429 Too Many Requests (not in HttpServletResponse's SC_* constants)
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate_limited\",\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }
}
