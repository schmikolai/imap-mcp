-- Phase 1/2 schema: first-party tenant users and their linked IMAP accounts.
-- OAuth-related tables (oauth_client, oauth_authorization_grant, oauth_token)
-- and Spring Authorization Server's own schema are added in a later migration
-- once the authorization server module lands (see plan phase 4).

CREATE TABLE tenant_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE imap_account (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_user_id              UUID NOT NULL REFERENCES tenant_user(id) ON DELETE CASCADE,
    display_name                VARCHAR(255) NOT NULL,
    host                        VARCHAR(255) NOT NULL,
    port                        INTEGER NOT NULL,
    tls_mode                    VARCHAR(16) NOT NULL DEFAULT 'IMPLICIT', -- IMPLICIT | STARTTLS
    username                    VARCHAR(320) NOT NULL,

    -- envelope-encrypted secret (see crypto/EncryptionService). encrypted_secret
    -- holds the packed nonce||ciphertext||tag produced by the local AEAD
    -- primitive (Tink's AesGcmJce) so nonce handling never leaks into
    -- application code.
    encrypted_secret            BYTEA NOT NULL,
    wrapped_dek                 BYTEA NOT NULL,
    kms_key_id                  VARCHAR(255) NOT NULL,
    key_version                 INTEGER NOT NULL DEFAULT 1,
    algorithm_id                VARCHAR(64) NOT NULL DEFAULT 'AES256_GCM',

    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION', -- PENDING_VERIFICATION | ACTIVE | LOCKED | NEEDS_REAUTH
    consecutive_auth_failures   INTEGER NOT NULL DEFAULT 0,
    locked_until                TIMESTAMPTZ,
    last_verified_at            TIMESTAMPTZ,

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_imap_account_tenant_display UNIQUE (tenant_user_id, display_name)
);

CREATE INDEX idx_imap_account_tenant_user ON imap_account(tenant_user_id);

CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_user_id  UUID REFERENCES tenant_user(id) ON DELETE SET NULL,
    imap_account_id UUID REFERENCES imap_account(id) ON DELETE SET NULL,
    oauth_client_id VARCHAR(255),
    token_id_hash   VARCHAR(128),
    tool_name       VARCHAR(128) NOT NULL,
    scope_used      VARCHAR(64),
    target_folder   VARCHAR(255),
    result_status   VARCHAR(32) NOT NULL,
    error_code      VARCHAR(64),
    latency_ms      INTEGER
);

CREATE INDEX idx_audit_log_tenant_user ON audit_log(tenant_user_id, occurred_at DESC);

-- Row-Level Security is deliberately NOT enabled yet: it depends on the
-- tenant-context plumbing (tenant/TenantContext, a Hibernate
-- filter/interceptor issuing `SET LOCAL app.tenant_id` per transaction)
-- landing first. Enabling the policy before that plumbing exists would make
-- `current_setting('app.tenant_id', true)` evaluate to NULL on every
-- connection and silently hide all rows from every query. See plan phase 5
-- ("Multi-tenant hardening") — the policy is added there in lockstep with
-- the code that sets the session variable, in the same migration.
