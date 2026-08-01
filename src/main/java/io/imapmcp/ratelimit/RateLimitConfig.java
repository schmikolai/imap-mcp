package io.imapmcp.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Backs {@link RateLimitFilter} with a Redis-distributed bucket store, so
 * limits hold even if this app ever runs as more than one instance — see
 * {@code TenantAwareDataSource}'s equivalent reasoning for why per-instance
 * (in-memory) state isn't good enough here either.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * A dedicated Lettuce client, separate from Spring Data Redis's own
     * {@code LettuceConnectionFactory} — bucket4j-redis's proxy manager
     * needs a raw {@link RedisClient}, not the higher-level abstraction
     * Spring Data Redis wraps it in. Reuses the same {@code spring.data.redis.*}
     * host/port config via the {@link RedisProperties} bean Spring Boot's
     * own Redis autoconfiguration already registers.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties redisProperties) {
        return RedisClient.create(RedisURI.create(redisProperties.getHost(), redisProperties.getPort()));
    }

    /**
     * Bucket keys are namespaced per rate-limited surface (e.g.
     * {@code "rl:mcp:"} + tenant id) by each {@code SecurityFilterChain}
     * that uses this, all sharing one Redis-backed proxy manager. The
     * expiration strategy caps how long an idle tenant/IP key lives in
     * Redis — otherwise every key seen once would live there forever.
     */
    @Bean
    public ProxyManager<String> bucket4jProxyManager(RedisClient redisClient) {
        ClientSideConfig clientSideConfig = ClientSideConfig.getDefault()
                .withExpirationAfterWriteStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)));
        ProxyManager<byte[]> byteKeyed = LettuceBasedProxyManager.builderFor(redisClient)
                .withClientSideConfig(clientSideConfig)
                .build();
        return byteKeyed.withMapper(key -> key.getBytes(StandardCharsets.UTF_8));
    }
}
