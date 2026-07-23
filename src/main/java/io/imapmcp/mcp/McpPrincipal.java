package io.imapmcp.mcp;

import java.util.UUID;

/**
 * The tenant + IMAP account an authenticated MCP client is authorized to
 * act on. In this phase it's resolved from a single static bearer token
 * (see {@link StaticBearerTokenAuthFilter}); once OAuth lands (plan phase 4)
 * it will be resolved from the access token's subject and granted scopes
 * instead, without any change to {@link ToolDispatcher}.
 */
public record McpPrincipal(UUID tenantUserId, UUID imapAccountId) {
}
