package io.imapmcp.mcp.dto;

import io.imapmcp.tenant.ImapAccount;

import java.time.Instant;
import java.util.UUID;

/** The {@code list_accounts} tool's view of one linked account — metadata only, never credentials. */
public record LinkedAccountSummary(UUID id, String displayName, String host, int port, String tlsMode,
                                    String username, String status, Instant createdAt) {

    public static LinkedAccountSummary from(ImapAccount account) {
        return new LinkedAccountSummary(
                account.getId(), account.getDisplayName(), account.getHost(), account.getPort(),
                account.getTlsMode().name(), account.getUsername(), account.getStatus().name(),
                account.getCreatedAt());
    }
}
