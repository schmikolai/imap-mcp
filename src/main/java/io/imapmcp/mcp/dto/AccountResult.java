package io.imapmcp.mcp.dto;

import java.util.UUID;

/**
 * One linked account's outcome within a tool call that can fan out across
 * every linked account (see {@code ToolDispatcher#withAccounts} — used by
 * {@code list_mailboxes}/{@code search_messages} when no {@code account}
 * argument is given). Exactly one of {@code data}/{@code error} is set, so a
 * failure on one account (e.g. locked out, IMAP connect failure) doesn't
 * discard results already fetched from the others.
 */
public record AccountResult<T>(UUID accountId, String accountDisplayName, T data, String error) {

    public static <T> AccountResult<T> ok(UUID accountId, String accountDisplayName, T data) {
        return new AccountResult<>(accountId, accountDisplayName, data, null);
    }

    public static <T> AccountResult<T> failed(UUID accountId, String accountDisplayName, String error) {
        return new AccountResult<>(accountId, accountDisplayName, null, error);
    }
}
