-- Spring Authorization Server's own schema (registered clients, in-flight/
-- issued authorizations, consents), adapted from the framework's official
-- schema for PostgreSQL: TEXT instead of VARCHAR/CLOB for the large JSON
-- attribute/metadata columns, and an explicit FK from oauth2_authorization
-- to oauth2_registered_client for referential integrity that upstream
-- leaves to the application.
--
-- Token VALUE columns (authorization_code_value, access_token_value,
-- refresh_token_value, oidc_id_token_value) store a SHA-256 hex hash of the
-- token, never the raw value — see auth/HashedTokenOAuth2AuthorizationService.
-- A DB leak alone therefore does not yield usable bearer tokens.

CREATE TABLE oauth2_registered_client (
    id                              VARCHAR(100) NOT NULL,
    client_id                       VARCHAR(100) NOT NULL,
    client_id_issued_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_secret                   VARCHAR(200) DEFAULT NULL,
    client_secret_expires_at        TIMESTAMPTZ DEFAULT NULL,
    client_name                     VARCHAR(200) NOT NULL,
    client_authentication_methods   VARCHAR(1000) NOT NULL,
    authorization_grant_types       VARCHAR(1000) NOT NULL,
    redirect_uris                   VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris       VARCHAR(1000) DEFAULT NULL,
    scopes                          VARCHAR(1000) NOT NULL,
    client_settings                 VARCHAR(2000) NOT NULL,
    token_settings                  VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_oauth2_registered_client_client_id ON oauth2_registered_client(client_id);

CREATE TABLE oauth2_authorization (
    id                              VARCHAR(100) NOT NULL,
    registered_client_id            VARCHAR(100) NOT NULL REFERENCES oauth2_registered_client(id),
    principal_name                  VARCHAR(200) NOT NULL,
    authorization_grant_type        VARCHAR(100) NOT NULL,
    authorized_scopes               VARCHAR(1000) DEFAULT NULL,
    attributes                      TEXT DEFAULT NULL,
    state                           VARCHAR(500) DEFAULT NULL,

    authorization_code_value        TEXT DEFAULT NULL,
    authorization_code_issued_at    TIMESTAMPTZ DEFAULT NULL,
    authorization_code_expires_at   TIMESTAMPTZ DEFAULT NULL,
    authorization_code_metadata     TEXT DEFAULT NULL,

    access_token_value              TEXT DEFAULT NULL,
    access_token_issued_at          TIMESTAMPTZ DEFAULT NULL,
    access_token_expires_at         TIMESTAMPTZ DEFAULT NULL,
    access_token_metadata           TEXT DEFAULT NULL,
    access_token_type               VARCHAR(100) DEFAULT NULL,
    access_token_scopes             VARCHAR(1000) DEFAULT NULL,

    oidc_id_token_value             TEXT DEFAULT NULL,
    oidc_id_token_issued_at         TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_expires_at        TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_metadata          TEXT DEFAULT NULL,

    refresh_token_value             TEXT DEFAULT NULL,
    refresh_token_issued_at         TIMESTAMPTZ DEFAULT NULL,
    refresh_token_expires_at        TIMESTAMPTZ DEFAULT NULL,
    refresh_token_metadata          TEXT DEFAULT NULL,

    user_code_value                 TEXT DEFAULT NULL,
    user_code_issued_at             TIMESTAMPTZ DEFAULT NULL,
    user_code_expires_at            TIMESTAMPTZ DEFAULT NULL,
    user_code_metadata               TEXT DEFAULT NULL,

    device_code_value                TEXT DEFAULT NULL,
    device_code_issued_at            TIMESTAMPTZ DEFAULT NULL,
    device_code_expires_at           TIMESTAMPTZ DEFAULT NULL,
    device_code_metadata             TEXT DEFAULT NULL,

    PRIMARY KEY (id)
);

CREATE INDEX idx_oauth2_authorization_principal ON oauth2_authorization(principal_name);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id   VARCHAR(100) NOT NULL REFERENCES oauth2_registered_client(id),
    principal_name          VARCHAR(200) NOT NULL,
    authorities             VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
