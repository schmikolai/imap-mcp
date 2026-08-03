package io.imapmcp.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a request ID into MDC (so every log line for a request is
 * correlatable in JSON output) and echoes it back on the response. Runs
 * ahead of all three {@code SecurityFilterChain}s — including authentication
 * failures — so it stays a genuine {@code @Component}: unlike
 * {@code TenantContextFilter}/{@code RateLimitFilter}, it's never also
 * manually wired via {@code addFilterAfter}/{@code addFilterBefore} into a
 * specific chain, so Spring Boot's normal global auto-registration of a
 * {@code Filter} bean is exactly what's wanted here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
