package io.imapmcp.imap;

import java.util.UUID;

public record AccountKey(UUID tenantUserId, UUID imapAccountId) {
}
