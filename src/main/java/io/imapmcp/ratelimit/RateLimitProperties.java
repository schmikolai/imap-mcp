package io.imapmcp.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.BucketConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-surface bucket sizing for {@link RateLimitFilter}, one bucket per
 * distinct attack surface (see {@code RateLimitConfig} for how each is
 * wired into its own {@link org.springframework.security.web.SecurityFilterChain}).
 */
@ConfigurationProperties(prefix = "imapmcp.rate-limit")
public class RateLimitProperties {

    private BucketSettings mcpToolCalls = new BucketSettings(60, Duration.ofMinutes(1));
    private BucketSettings oauthEndpoints = new BucketSettings(20, Duration.ofMinutes(1));
    private BucketSettings webAuth = new BucketSettings(10, Duration.ofMinutes(1));

    public BucketSettings getMcpToolCalls() {
        return mcpToolCalls;
    }

    public void setMcpToolCalls(BucketSettings mcpToolCalls) {
        this.mcpToolCalls = mcpToolCalls;
    }

    public BucketSettings getOauthEndpoints() {
        return oauthEndpoints;
    }

    public void setOauthEndpoints(BucketSettings oauthEndpoints) {
        this.oauthEndpoints = oauthEndpoints;
    }

    public BucketSettings getWebAuth() {
        return webAuth;
    }

    public void setWebAuth(BucketSettings webAuth) {
        this.webAuth = webAuth;
    }

    public static class BucketSettings {
        private long capacity;
        private Duration refillPeriod;

        public BucketSettings() {
        }

        public BucketSettings(long capacity, Duration refillPeriod) {
            this.capacity = capacity;
            this.refillPeriod = refillPeriod;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public Duration getRefillPeriod() {
            return refillPeriod;
        }

        public void setRefillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
        }

        /**
         * A plain "consume up to {@code capacity} tokens per
         * {@code refillPeriod}, refilled all at once" bucket — simple to
         * reason about for these low-volume abuse-prevention limits, as
         * opposed to a smoothed/rolling-window refill.
         */
        public BucketConfiguration toBucketConfiguration() {
            Bandwidth limit = BandwidthBuilder.builder()
                    .capacity(capacity)
                    .refillGreedy(capacity, refillPeriod)
                    .build();
            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        }
    }
}
