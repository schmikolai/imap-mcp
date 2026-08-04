-- Read-only role for an external Grafana instance to query audit_log
-- directly as a PostgreSQL data source. Deliberately scoped to SELECT on
-- just that one table — not the imapmcp_app role (which has read/write on
-- every table) and not the schema owner.
--
-- Note: audit_log has no Row-Level Security policy (see V3's comment on why
-- it's deferred), so this role can see every tenant's rows. That's expected
-- for an internal ops dashboard, not a bug.
--
-- ${grafanaDbUser}/${grafanaDbPassword} are Flyway placeholders (see
-- spring.flyway.placeholders.* in application.yml) so no password is
-- hardcoded in a committed file.
CREATE ROLE ${grafanaDbUser} LOGIN PASSWORD '${grafanaDbPassword}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT USAGE ON SCHEMA public TO ${grafanaDbUser};
GRANT SELECT ON audit_log TO ${grafanaDbUser};
