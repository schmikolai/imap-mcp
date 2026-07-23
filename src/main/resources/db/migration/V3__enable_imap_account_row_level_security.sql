-- Enables the Row-Level Security policy that V1's migration deliberately
-- left disabled until the tenant-context plumbing existed (see
-- tenant/TenantAwareDataSource, tenant/TenantContextFilter — both land in
-- this same change) to issue `SELECT set_config('app.tenant_id', ..., true)`
-- (the SQL-callable equivalent of SET LOCAL) as the first statement of
-- every transaction.
--
-- FORCE ROW LEVEL SECURITY is essential, not optional: by default Postgres
-- RLS policies do NOT apply to a table's owner, and the application's DB
-- role is also the role that owns this table (it ran the migrations that
-- created it). Without FORCE, this policy would be silently inert for our
-- own application queries — a false sense of security — and would only
-- ever apply to some other, non-owning role.
ALTER TABLE imap_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE imap_account FORCE ROW LEVEL SECURITY;

CREATE POLICY imap_account_tenant_isolation ON imap_account
    USING (tenant_user_id::text = current_setting('app.tenant_id', true));
