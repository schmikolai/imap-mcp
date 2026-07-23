package io.imapmcp.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds the AEAD associated-authenticated-data (AAD) that binds a stored
 * ciphertext to the exact row it belongs to, so a ciphertext copied or
 * substituted between rows (e.g. across tenants) fails AEAD authentication
 * instead of silently decrypting.
 */
public final class AssociatedData {

    private AssociatedData() {
    }

    public static byte[] forImapAccount(UUID tenantUserId, UUID imapAccountId) {
        String context = "imap_account|" + tenantUserId + "|" + imapAccountId;
        return context.getBytes(StandardCharsets.UTF_8);
    }
}
