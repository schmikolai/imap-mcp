package io.imapmcp.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RateLimitFilter} directly against local (non-distributed)
 * bucket4j buckets — no Redis needed, since the filter is deliberately
 * Redis-agnostic (see its javadoc).
 */
class RateLimitFilterTest {

    private static final long CAPACITY = 2;

    @Test
    void allowsRequestsWithinCapacityThenBlocks() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(bucketResolver(), request -> "same-key", null,
                new SimpleMeterRegistry(), "test");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = countingChain(chainInvocations);

        assertThat(consume(filter, chain)).isEqualTo(200);
        assertThat(consume(filter, chain)).isEqualTo(200);
        assertThat(chainInvocations.get()).isEqualTo(2);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(chainInvocations.get()).isEqualTo(2);
    }

    @Test
    void independentKeysGetIndependentBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(bucketResolver(), RateLimitFilterTest::keyAttribute, null,
                new SimpleMeterRegistry(), "test");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = countingChain(chainInvocations);

        for (int i = 0; i < CAPACITY; i++) {
            filter.doFilter(requestWithKey("tenant-a"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse tenantAExhausted = new MockHttpServletResponse();
        filter.doFilter(requestWithKey("tenant-a"), tenantAExhausted, chain);
        assertThat(tenantAExhausted.getStatus()).isEqualTo(429);

        MockHttpServletResponse tenantBFresh = new MockHttpServletResponse();
        filter.doFilter(requestWithKey("tenant-b"), tenantBFresh, chain);
        assertThat(tenantBFresh.getStatus()).isEqualTo(200);
    }

    @Test
    void requestsOutsideScopeMatcherAlwaysPassThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(bucketResolver(), request -> "same-key",
                new AntPathRequestMatcher("/login", "POST"), new SimpleMeterRegistry(), "test");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = countingChain(chainInvocations);

        MockHttpServletRequest unrelated = new MockHttpServletRequest("GET", "/some-other-path");
        for (int i = 0; i < CAPACITY + 5; i++) {
            filter.doFilter(unrelated, new MockHttpServletResponse(), chain);
        }

        assertThat(chainInvocations.get()).isEqualTo((int) CAPACITY + 5);
    }

    private static java.util.function.Function<String, Bucket> bucketResolver() {
        ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
        return key -> buckets.computeIfAbsent(key, k -> {
            Bandwidth limit = BandwidthBuilder.builder()
                    .capacity(CAPACITY)
                    .refillGreedy(CAPACITY, Duration.ofMinutes(1))
                    .build();
            return Bucket.builder().addLimit(limit).build();
        });
    }

    private static int consume(RateLimitFilter filter, FilterChain chain) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, chain);
        return response.getStatus();
    }

    private static MockHttpServletRequest requestWithKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("key", key);
        return request;
    }

    private static FilterChain countingChain(AtomicInteger counter) {
        return (req, res) -> counter.incrementAndGet();
    }

    private static String keyAttribute(HttpServletRequest request) {
        return (String) request.getAttribute("key");
    }
}
