package io.imapmcp.imap;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.springframework.stereotype.Component;

/**
 * IMAP {@link Store} connections keyed per (tenant, imap account) — never
 * shared across tenants or accounts. Capped per-account so one tenant can't
 * exhaust connections meant for others (see resilience4j bulkhead for
 * cross-tenant throughput fairness, added in a later phase).
 */
@Component
public class ImapConnectionPool {

    private final GenericKeyedObjectPool<AccountKey, Store> pool;

    public ImapConnectionPool(ImapStorePooledFactory factory, ImapProperties properties) {
        GenericKeyedObjectPoolConfig<Store> config = new GenericKeyedObjectPoolConfig<>();
        config.setMaxTotalPerKey(properties.getMaxConnectionsPerAccount());
        config.setMaxIdlePerKey(properties.getMaxConnectionsPerAccount());
        config.setTestOnBorrow(true);
        config.setMinEvictableIdleDuration(java.time.Duration.ofMinutes(2));
        config.setTimeBetweenEvictionRuns(java.time.Duration.ofMinutes(1));
        this.pool = new GenericKeyedObjectPool<>(factory, config);
    }

    public Store borrow(AccountKey key) throws Exception {
        return pool.borrowObject(key);
    }

    public void returnStore(AccountKey key, Store store) {
        pool.returnObject(key, store);
    }

    public void invalidate(AccountKey key, Store store) {
        try {
            pool.invalidateObject(key, store);
        } catch (Exception ignored) {
            // best-effort eviction of a broken connection
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.close();
    }
}
