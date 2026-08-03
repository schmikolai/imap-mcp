package io.imapmcp.mcp;

import java.util.UUID;

/**
 * The tenant an authenticated MCP client is authorized to act on, resolved
 * from the OAuth access token's subject (see
 * {@link JwtMcpAuthenticationConverter}). A grant is tenant-wide, not
 * account-specific — it stays valid for accounts the tenant links after the
 * grant was made. Deliberately carries no {@code imapAccountId}: which of
 * the tenant's (possibly several) linked {@code ImapAccount}s a given tool
 * call touches is resolved per-call by {@link ToolDispatcher}, from an
 * optional/required {@code account} tool argument (see {@link ToolRegistry}).
 */
public record McpPrincipal(UUID tenantUserId) {
}
