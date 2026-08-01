# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An open-source MCP (Model Context Protocol) server that lets AI agents organize a user's email over IMAP — search/read mail, manage folders, move/delete messages, mark read/unread — as a hosted multi-tenant SaaS. Users link an IMAP account (password/app-password, encrypted server-side); AI agents then connect via OAuth 2.1 and never see the raw IMAP password. Java 21 + Spring Boot 3.3.4, single modular-monolith deployable (not microservices).

Security is the central design constraint here, not an afterthought: this endpoint is assumed to run in a hostile public environment. Preserve that posture in any change — see "Hard-won gotchas" below before touching auth, crypto, or the security filter chains.

See [README.md](README.md) for the MCP tool-to-scope table and the full list of configuration environment variables — not duplicated here.

## Commands

Build/run (Gradle wrapper, no local Gradle install needed):
```
./gradlew compileJava                 # compile main sources
./gradlew compileTestJava             # compile test sources
./gradlew test                        # run all tests
./gradlew test --tests "io.imapmcp.crypto.*"                              # one package
./gradlew test --tests "io.imapmcp.crypto.AwsKmsEnvelopeEncryptionServiceTest"  # one class
./gradlew bootRun --args='--spring.profiles.active=local'                # run the app locally
```

Local dev environment (Postgres + Redis via Docker, no AWS account needed):
```
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```
The `local` profile (`application-local.yml`) points at the compose services and activates `LocalDevEncryptionService` in place of real AWS KMS, and seeds a test OAuth client (`local-dev-client` / `local-dev-secret`, redirect `http://localhost:9999/callback`). Never use this profile against real user data — see the class-level Javadoc on `LocalDevEncryptionService`.

`ImapMailServiceIntegrationTest` (GreenMail + Testcontainers) additionally needs: Docker with API version ≥ 1.40 (older docker-java/Testcontainers versions may not negotiate correctly against restrictive Docker socket proxies), and `keytool` on PATH (it generates a throwaway self-signed cert at `@BeforeAll` so GreenMail can serve real implicit TLS — production code path is exercised unmodified, never relaxed for tests).

Docker image (plain JVM — a multi-stage `Dockerfile`, JDK builder → JRE runtime):
```
docker build -t imap-mcp .
```

## Architecture

### Package map (`io.imapmcp.*`)

- `auth/` — the OAuth 2.1 Authorization Server: `AuthorizationServerConfig` (Spring Authorization Server wiring, JWK/signing key, token customizer), `HashedTokenOAuth2AuthorizationService`, `OAuthClientSeeder` (static allow-list, no open Dynamic Client Registration), `ConsentController` (custom consent page).
- `config/` — the three `SecurityFilterChain` beans and their explicit ordering (see below).
- `crypto/` — envelope encryption for stored IMAP passwords: `EncryptionService` interface, `AwsKmsEnvelopeEncryptionService` (prod default) vs `LocalDevEncryptionService` (`@Profile("local")`), `AssociatedData` (AAD binding), `SecureImapPassword` (zeroable wrapper).
- `imap/` — the IMAP bridge: connection pooling (`ImapConnectionPool`, `ImapStorePooledFactory`), session/TLS setup (`ImapSessionFactory`), account linking + lockout (`ImapAccountLinkingService`, `ImapAccountLockoutService`), the actual mail actions (`ImapMailService`), MIME parsing/sanitization (`MimeContentExtractor`).
- `mcp/` — the MCP protocol surface: hand-rolled JSON-RPC 2.0 (`mcp/jsonrpc/`) over Streamable HTTP (`McpController`), tool schemas (`ToolRegistry`), tool execution + per-tool scope enforcement (`ToolDispatcher`), OAuth-JWT-to-principal resolution (`JwtMcpAuthenticationConverter`, `McpAuthenticationToken`).
- `tenant/` — JPA entities/repositories for the human account (`TenantUser`) and their linked IMAP accounts (`ImapAccount`), plus the Postgres RLS tenant-context plumbing (`TenantContext`, `TenantAwareDataSource`, `TenantContextFilter`, `TenantDataSourceConfig` — see "Multi-tenant isolation" below).
- `web/` — first-party (human) signup/login/account-linking controllers and Thymeleaf templates.

### Security filter chain ordering (three chains, one app)

Defined across `AuthorizationServerConfig`, `McpSecurityConfig`, and `SecurityConfig`, distinguished by explicit `@Order`:

1. `@Order(0)` — OAuth2 Authorization Server endpoints (`/oauth2/**`), matched via `authorizationServerConfigurer.getEndpointsMatcher()`.
2. `@Order(1)` — `/mcp/**`, OAuth2 resource server validating JWTs issued by chain 1.
3. `@Order(2)` — everything else: first-party form login for the human web UI (signup/login/account linking).

These three identities must never share a session/credential model — that's the whole point of the split. When adding a new endpoint, check which chain's matcher it falls under before assuming `permitAll`/`authenticated` rules from one chain apply.

### OAuth token flow

Auth-code + mandatory PKCE (S256) → custom Thymeleaf consent page (not Spring AS's built-in one — see gotchas) → JWT access token (short-lived) + rotating opaque refresh token, both persisted as SHA-256 hashes (`HashedTokenOAuth2AuthorizationService`), never raw. Access tokens carry `scope` claims (`mcp:mail.read` / `mcp:mail.write` / `mcp:mailbox.manage` / `mcp:mail.delete`, see `McpScopes`) that `ToolDispatcher.REQUIRED_SCOPE` enforces per tool before it ever touches IMAP — the most destructive action (delete) requires its own scope, separate from read/write. `JwtMcpAuthenticationConverter` resolves the JWT's `sub` (the user's login email) to a `TenantUser`, then to their **first** linked `ImapAccount` — multi-account selection during consent is a known future enhancement, not yet built.

### Credential encryption

Per-`ImapAccount` envelope encryption: a KMS-generated AES-256 DEK encrypts the password locally (Tink's `AesGcmJce`), only the KMS-wrapped DEK is persisted, plaintext DEK is zeroed after use. AAD binds each ciphertext to its exact `(tenant_id, imap_account_id)` row (`AssociatedData`), so a ciphertext copied between rows fails to decrypt instead of silently succeeding. `EncryptionService` is the swappable interface; `AwsKmsEnvelopeEncryptionService` (`@Profile("!local")`) is the real implementation, `LocalDevEncryptionService` (`@Profile("local")`) is a same-process-only stand-in with no KMS dependency, used for local dev and by tests.

### MCP protocol surface

Deliberately scoped down from the full spec: no JSON-RPC batching (dropped from the current spec anyway), no server-initiated SSE (GET returns 405), sessions tracked in-memory only (`McpSessionRegistry` — fine for one instance, would need Redis-backing for horizontal scale). Tool execution failures (bad folder, message not found, insufficient scope, IMAP connection failure) are returned as `isError: true` tool results, not JSON-RPC protocol errors, per MCP convention — only malformed requests (unknown tool, bad params) become protocol-level errors.

### Multi-tenant isolation (Postgres RLS)

`imap_account` has a Postgres Row-Level Security policy (`V3__enable_imap_account_row_level_security.sql`) as defense-in-depth *underneath* the application's own `findByIdAndTenantUserId`/`findByTenantUserId`-scoped queries — a raw, unscoped `SELECT * FROM imap_account` returns only the current tenant's row (or zero rows with no tenant context set; it fails closed). This only works because of two more pieces, both required together:

- **`V4__create_least_privilege_app_role.sql`** creates `imapmcp_app`, a non-superuser/`NOBYPASSRLS` role that the app connects as for all runtime queries (`spring.datasource.*`). Flyway itself connects separately as the schema-owning role (`spring.flyway.url/user/password`) to run DDL. This split exists because Postgres RLS — even with `FORCE ROW LEVEL SECURITY` — **never applies to a superuser**, and the official `postgres` Docker image's `POSTGRES_USER` is created as one.
- **`tenant/TenantAwareDataSource`** sets Postgres's `app.tenant_id` session variable (via `set_config(..., is_local => false)`) on *every* `getConnection()` call, populated from **`tenant/TenantContext`** (a `ThreadLocal`) — read by the RLS policy. `tenant/TenantContextFilter` populates `TenantContext` from whichever principal is authenticated (`McpPrincipal` directly, or a web-session `UserDetails` resolved via `TenantUserRepository`), added to both the web and MCP `SecurityFilterChain`s via `addFilterAfter(_, AuthorizationFilter.class)`.

### What's stubbed vs. not yet built

- `bucket4j`/`resilience4j` are on the classpath but not yet wired to anything — rate limiting and circuit-breaking are unimplemented.
- No audit-log persistence yet (the `audit_log` table exists in `V1` but nothing writes to it).
- Redis is a live dependency (`docker-compose.yml`, `spring-boot-starter-data-redis`) but nothing currently requires it to be running for the app to boot — it becomes load-bearing once rate limiting is implemented.
- Per-grant IMAP account selection during OAuth consent (see the OAuth token flow note above) — currently always the tenant's first linked account.

## Hard-won gotchas

These were each found by actually running the app, not by code review — re-introducing any of them is a real regression, not a style nit.

- **`/error` must be in the web chain's `permitAll`.** Spring Boot forwards any error response (a 401, a 404, an uncaught exception) to `/error` internally. If that path itself requires auth, an unauthenticated error becomes a redirect-to-login that masks the real error status/body entirely.
- **Never make a custom filter a `@Component`/`@Bean` if it's also wired via `addFilterBecore`/`addFilterAfter` into a specific `SecurityFilterChain`.** Spring Boot auto-registers any `Filter` bean as a global servlet filter applying to *every* URL, in addition to wherever you placed it explicitly — this once made an MCP-only auth filter intercept the human login pages too. Construct such filters with `new` inside the config method instead.
- **`ImapAccount.id` is assigned client-side (`UUID.randomUUID()` in the constructor), not `@GeneratedValue`.** The AAD binding a secret's ciphertext to its row must be computed *before* the row is ever inserted (encrypted_secret/wrapped_dek are `NOT NULL`), so the ID has to exist before the only `save()` call.
- **`ImapAccountLinkingService.linkAccount()` must not be `@Transactional`.** Each `save()` inside it must commit independently, so a failed IMAP test-connect still leaves the account row persisted as `NEEDS_REAUTH` (for retry/audit) instead of the whole insert being rolled back by the exception `verifyConnection` throws.
- **No `@Lob` on the `byte[]` crypto columns in `ImapAccount`.** Hibernate 6 + PostgreSQL can map `@Lob byte[]` to the `oid` large-object type instead of `bytea`; plain `byte[]` uses the default `VARBINARY` mapping that actually matches the migration's `BYTEA` columns.
- **`HashedTokenOAuth2AuthorizationService` only hashes `code`/`access_token`/`refresh_token` token types.** The OAuth `state` parameter (used to correlate the pre-consent authorization request) is stored raw by the superclass; hashing it on lookup breaks every consent submission with a cryptic `invalid_request: state`.
- **CSRF is exempted only for `/oauth2/token`, `/oauth2/introspect`, `/oauth2/revoke`** (client-credential authenticated, not cookie/session-based — CSRF protection there is a false positive, not a real defense). `/oauth2/authorize` and `/oauth2/consent` keep CSRF protection since they're genuinely browser-session-driven.
- **Spring Authorization Server's built-in default consent page cannot carry a CSRF token** (it's rendered as raw HTML outside Spring MVC/Thymeleaf). That's why there's a custom `ConsentController` + `consent.html` instead of the framework default.
- **Never use `List.of(...)` for anything that ends up in an `OAuth2Authorization`'s persisted metadata** (e.g. custom JWT claims added in a token customizer). The JDK-internal `ImmutableCollections$List12` it returns isn't in Spring Security's Jackson deserialization allowlist, and 500s the next refresh/introspection read-back. Use a plain `ArrayList`.
- **Static, self-registered filters need real config properties, not blank env-var defaults bound to typed fields.** Binding an empty string to a `UUID`/typed `@ConfigurationProperties` field throws at context startup — prefer `String` fields parsed defensively when a value is legitimately optional.
- **A Postgres superuser always bypasses RLS, `FORCE ROW LEVEL SECURITY` included.** The official `postgres` Docker image's `POSTGRES_USER` is a superuser. Testing RLS against that role will always "pass" whether or not the policy actually works — test against the real least-privilege runtime role (`imapmcp_app`) instead, or the result is a false negative (or false positive) about whether isolation actually holds.
- **Don't hook `Connection.setAutoCommit(false)` to detect "a transaction is starting."** It's a reasonable-sounding idea (`TenantAwareDataSource` tried it first) but empirically, on this stack, a read-only `@Transactional` repository method never calls it at all — there's no reliable signal to hook. Apply session state (like RLS's `app.tenant_id`) unconditionally in `DataSource.getConnection()` instead, using `set_config(..., is_local => false)` (session-scoped) rather than `SET LOCAL` (transaction-scoped, and a no-op outside an active transaction block anyway) — and always set *something* (empty string if no tenant context), since the physical connection is pooled and reused across unrelated later checkouts.
- **A `@Primary` bean can't have its own constructor/factory-method parameter of the same type be resolved by type alone** — Spring tries to satisfy it with the primary candidate, i.e. itself, and refuses to start over the circular reference. When wrapping/decorating a bean of the same type it produces (e.g. `TenantAwareDataSource` wrapping the app's `DataSource`), give the wrapped, non-primary instance its own explicitly-named `@Bean` method and inject it by that name — don't rely on `@Qualifier` guessing Spring Boot's internal auto-configured bean name (it isn't necessarily `"dataSource"`).
- **Flyway placeholder map keys (`spring.flyway.placeholders.*`) are taken verbatim from the property source — not relaxed-bound like ordinary `@ConfigurationProperties` fields.** A YAML key of `app-db-user` does **not** satisfy a `${appDbUser}` token in migration SQL; keep the YAML key and the SQL placeholder token in matching case (camelCase is simplest for both).
- **A `Converter<Jwt, AbstractAuthenticationToken>` that itself queries an RLS-protected table has a chicken-and-egg problem with `TenantContextFilter`:** the converter runs as part of authentication, *before* any filter added via `addFilterAfter(_, AuthorizationFilter.class)` ever executes. `JwtMcpAuthenticationConverter` sets `TenantContext` itself, immediately after resolving the tenant and before its own `imap_account` lookup — waiting for `TenantContextFilter` means that lookup runs with no tenant context set and gets zero rows back under RLS's fail-closed default (surfaces as a confusing "user has no linked IMAP account" for an account that clearly exists).
- **Don't use GraalVM native-image for this app.** It was tried (multi-stage `Dockerfile` + `org.graalvm.buildtools.native` + hand-written `reflect-config.json`) and removed after `JdbcRegisteredClientRepository`'s Jackson-based `client_settings`/`token_settings` deserialization broke under native-image at runtime (`UnmodifiableMapDeserializer has no default (no arg) constructor` on every restart after the first successful OAuth client seed) — confirmed via a real native build, identical DB row read fine on plain JVM and failed every time under native-image. Root cause sits deep in Spring Authorization Server's + Jackson's polymorphic (`@JsonTypeInfo(use = Id.CLASS)`) type resolution under native-image's closed-world reflection model, not something fixable with a small reflect-config addition. Docker image is a plain multi-stage JDK-builder → JRE-runtime `Dockerfile` now.
