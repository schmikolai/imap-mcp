package io.imapmcp.tenant;

import java.util.UUID;

/**
 * The tenant the current thread is acting on behalf of, for the duration of
 * one request. Read by {@link TenantAwareDataSource} to bind Postgres RLS's
 * {@code app.tenant_id} session variable at the start of each transaction —
 * see that class for why this is necessary at all.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantUserId) {
        CURRENT.set(tenantUserId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
