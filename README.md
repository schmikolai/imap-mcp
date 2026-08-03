# imap-mcp

An open-source [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that lets AI agents organize your email over IMAP — search, read, move, mark read/unread, and delete messages — as a hosted, multi-tenant service.

You register your IMAP account once (password or app-password); it's encrypted and never touches the agent directly. AI agents connect through a real OAuth 2.1 authorization flow with your explicit, scoped consent — an agent granted "read" access never gets "delete", for example. The agent itself never sees your IMAP password.

This project assumes it runs in a hostile public environment and is built accordingly: application-level security is the central design constraint, not an afterthought.

## Security model

- **Credentials are envelope-encrypted at rest.** Each linked IMAP account gets its own AES-256 data-encryption key, generated via AWS KMS; only the KMS-wrapped key is stored, never the plaintext key or password. Each ciphertext is cryptographically bound to its exact account row, so it can't be swapped between accounts even in the event of a database compromise.
- **Agents authenticate via OAuth 2.1**, not shared API keys: authorization-code flow with mandatory PKCE, a real consent screen, short-lived JWT access tokens, and rotating refresh tokens. Tokens are stored as SHA-256 hashes — a database leak alone doesn't yield usable bearer tokens.
- **Least-privilege scopes per action.** An agent's token only grants the specific permissions consented to (`read`, `write`, `manage folders`, `delete`) — enforced before any IMAP call is made. Delete is deliberately its own scope, separate from read/write.
- **Database-level tenant isolation**, not just application code. Postgres Row-Level Security enforces that one tenant's data is invisible to another, even from a raw, unscoped query — a genuine second layer of defense, not a restatement of the application's own `WHERE` clauses. The app connects as a deliberately unprivileged database role, since RLS means nothing to a superuser.
- **IMAP itself is hardened**: TLS certificate validation is never relaxed (including in tests), agent-supplied folder names and search terms are never string-concatenated into raw IMAP commands, fetched HTML is sanitized, attachments are metadata-only by default, and repeated auth failures trigger lockout/backoff.

See [CLAUDE.md](CLAUDE.md) for the full architectural detail, including hard-won implementation gotchas.

## Status

Built in phases; phases 1–5 are implemented and verified end-to-end against a real running instance (not just written — actually exercised: signup/login, IMAP account linking, the full OAuth authorization-code+PKCE+consent flow, MCP tool calls, and cross-tenant isolation at the database level).

- [x] **1 — Foundations**: Spring Boot skeleton, Postgres schema, first-party signup/login
- [x] **2 — Credential vault + IMAP core**: envelope encryption, IMAP connection pooling, the core mail actions
- [x] **3 — MCP protocol surface**: JSON-RPC/Streamable HTTP, tool schemas, tool dispatch
- [x] **4 — OAuth 2.1 Authorization Server**: auth-code+PKCE, custom consent screen, hashed token storage, per-tool scopes
- [x] **5 — Multi-tenant hardening**: Postgres Row-Level Security, least-privilege database role
- [ ] **6 — Abuse controls & observability**: rate limiting, IMAP auth-failure circuit breakers, audit log persistence
- [ ] **7 — Key rotation & operational maturity**: DEK rotation job, revocation cascades, pre-launch security review

Not yet built: open Dynamic Client Registration (agent clients are currently a static, manually-vetted allow-list), and per-grant IMAP account selection during consent (a tenant's *first* linked account is used).

## Requirements

- JDK 21
- Docker (for Postgres + Redis locally — no local Postgres/Redis install needed)
- An AWS account with a KMS key, for production use. **Not needed for local development** — see below.

## Quickstart (local development)

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

That's it. On first boot, Flyway creates the whole schema *and* the least-privilege database role automatically. The `local` profile swaps AWS KMS for an in-process encryption stand-in (no AWS account needed) and seeds a test OAuth client (`local-dev-client` / `local-dev-secret`, redirect `http://localhost:9999/callback`) so you can exercise the full OAuth flow without a real agent.

Then:
1. Visit `http://localhost:8080/signup` and create an account.
2. Log in, then link a real IMAP account (a Gmail app-password works well for testing) at `/accounts/link`.
3. Point an OAuth-capable MCP client at `http://localhost:8080` (metadata: `/.well-known/oauth-authorization-server`) to connect an agent.

**Never point the `local` profile at real user data or deploy it as-is** — its encryption stand-in has no KMS backing at all (see `LocalDevEncryptionService`'s Javadoc).

## MCP tools

| Tool | Scope required | Description |
|---|---|---|
| `list_mailboxes` | `mcp:mail.read` | List all mailboxes/folders in the connected account |
| `search_messages` | `mcp:mail.read` | Search a mailbox by subject, sender, unread status, and/or date |
| `read_message` | `mcp:mail.read` | Fetch full message content (sanitized HTML, attachment metadata only) |
| `create_mailbox` | `mcp:mailbox.manage` | Create a new mailbox/folder |
| `move_message` | `mcp:mail.write` | Move a message between mailboxes |
| `mark_message` | `mcp:mail.write` | Mark a message read or unread |
| `trash_message` | `mcp:mail.delete` | Move a message to Trash (soft delete, not permanent expunge) |

## Configuration

Key environment variables (all have sane local-dev defaults via `application-local.yml`):

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD` | The app's own runtime database connection (least-privilege role) |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | Schema-owning role Flyway uses to run migrations |
| `KMS_KEY_ID`, `AWS_REGION` | AWS KMS key for credential encryption (production only) |
| `OAUTH_ISSUER_URI` | This server's own OAuth issuer identity — must be a fixed, real URL in production |
| `OAUTH_SEED_CLIENT_ID`, `OAUTH_SEED_CLIENT_SECRET`, `OAUTH_SEED_CLIENT_NAME`, `OAUTH_SEED_CLIENT_REDIRECT_URIS` | One statically-vetted OAuth client to seed on startup (optional — leave `OAUTH_SEED_CLIENT_ID` unset to skip) |
| `REDIS_HOST`, `REDIS_PORT` | Redis connection — load-bearing for distributed rate limiting (see `ratelimit/`) |
| `PORT` | HTTP port (default `8080`) |
| `MANAGEMENT_PORT` | Actuator/Prometheus port (default `8081`) — never publish this externally, only network isolation keeps `/actuator/prometheus` unauthenticated (see CLAUDE.md) |

## Testing

```bash
./gradlew test                                                    # everything
./gradlew test --tests "io.imapmcp.crypto.*"                      # one package
```

`ImapMailServiceIntegrationTest` additionally needs Docker (API ≥ 1.40) and `keytool` on `PATH` — it spins up a real GreenMail IMAP server with implicit TLS to exercise the production code path unmodified.

## License

[GNU AGPLv3](LICENSE.txt).
