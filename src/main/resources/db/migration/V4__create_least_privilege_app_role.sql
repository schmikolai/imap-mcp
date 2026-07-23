-- Row-Level Security is meaningless if the connecting role is a Postgres
-- superuser: superusers (and any role with BYPASSRLS) always bypass RLS,
-- and FORCE ROW LEVEL SECURITY explicitly does not override that. The
-- official postgres Docker image's POSTGRES_USER is created as a
-- superuser — great for running migrations, useless for proving RLS
-- actually does anything.
--
-- This migration creates a separate, deliberately unprivileged role for the
-- application's own runtime queries (NOSUPERUSER, NOBYPASSRLS, no DDL
-- rights) — distinct from the role that owns the tables and runs Flyway
-- migrations. ${appDbUser}/${appDbPassword} are Flyway placeholders (see
-- spring.flyway.placeholders.* in application.yml) so no password is
-- hardcoded in a committed file.
CREATE ROLE ${appDbUser} LOGIN PASSWORD '${appDbPassword}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${appDbUser};
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ${appDbUser};

-- So this role automatically gets the same grants on tables/sequences
-- created by LATER migrations too, without needing a fresh GRANT each time.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${appDbUser};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO ${appDbUser};
