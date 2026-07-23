package io.imapmcp.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Wraps the real {@link DataSource} so that every connection checkout has
 * Postgres's {@code app.tenant_id} session variable set to
 * {@link TenantContext#get()} — the variable the RLS policy on
 * {@code imap_account} (see the V3 migration) checks.
 *
 * <p>Applied with {@code set_config(..., is_local => false)} (session-
 * scoped), not {@code SET LOCAL}/{@code is_local => true}
 * (transaction-scoped), and unconditionally on every {@code getConnection()}
 * call rather than hooked to a transaction-start signal. That's a deliberate
 * correction: connections here come out of the pool with autocommit already
 * {@code true}, and — empirically, not just per Hibernate's docs — a
 * read-only {@code @Transactional} repository method never calls
 * {@code Connection.setAutoCommit(false)} at all, so there is no reliable
 * "transaction is starting" signal to hook on this JDBC pool. Since a
 * physical connection is reused across unrelated later checkouts
 * (potentially for a different tenant, or no tenant at all — e.g. Flyway's
 * separate connection, or the OAuth authorization server's own queries),
 * every checkout must both set the CURRENT tenant and clear any stale value
 * a previous checkout may have left behind: an empty string never matches a
 * real {@code tenant_user_id}, so an absent {@link TenantContext} fails
 * closed rather than silently reusing whoever queried last.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantContext(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantContext(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection applyTenantContext(Connection connection) throws SQLException {
        UUID tenantId = TenantContext.get();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }
        return connection;
    }
}
