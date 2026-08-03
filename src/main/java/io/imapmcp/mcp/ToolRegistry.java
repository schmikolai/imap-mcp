package io.imapmcp.mcp;

import io.imapmcp.mcp.dto.JsonSchemas;
import io.imapmcp.mcp.dto.McpToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tool surface exposed to MCP clients — one entry per action from the
 * original requirement set (query/read mail, manage folders, move, trash,
 * mark read), plus {@code list_accounts} for discovering which linked
 * accounts exist. An OAuth grant authorizes a tenant, not a single account
 * (see {@link McpPrincipal}), so every tool but {@code list_accounts} takes
 * an {@code account} argument identifying which linked account it targets,
 * by id as returned from {@code list_accounts}:
 *
 * <ul>
 *   <li>Required on tools that act on one existing message/folder
 *       ({@code read_message}, {@code move_message}, {@code mark_message},
 *       {@code trash_message}, {@code create_mailbox}) — a UID or folder
 *       name alone can't tell two linked accounts' mail apart, so there's
 *       no safe default, and none of these ever act across more than one
 *       account.
 *   <li>Optional on the two read-only listing tools ({@code
 *       list_mailboxes}, {@code search_messages}) — omitting it changes
 *       the output by fanning out across every linked account instead of
 *       one, which is a legitimate "give me everything" query.
 * </ul>
 */
@Component
public class ToolRegistry {

    private final List<McpToolDefinition> tools = buildTools();

    public List<McpToolDefinition> list() {
        return tools;
    }

    public Optional<McpToolDefinition> find(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    private static List<McpToolDefinition> buildTools() {
        return List.of(
                new McpToolDefinition(
                        "list_accounts",
                        "List every IMAP account linked to this tenant (id, display name, host, status). "
                                + "Call this first if you don't already know which account id to use — every "
                                + "other tool that targets one account needs an id from here.",
                        JsonSchemas.object(Map.of(), List.of())),

                new McpToolDefinition(
                        "list_mailboxes",
                        "List all mailboxes/folders in a linked IMAP account. Omit 'account' to list "
                                + "mailboxes across every linked account instead of just one.",
                        JsonSchemas.object(Map.of("account", accountSchema(false)), List.of())),

                new McpToolDefinition(
                        "create_mailbox",
                        "Create a new mailbox/folder in one linked account.",
                        JsonSchemas.object(
                                Map.of(
                                        "account", accountSchema(true),
                                        "name", JsonSchemas.string("Folder name to create")),
                                List.of("account", "name"))),

                new McpToolDefinition(
                        "search_messages",
                        "Search messages in a mailbox by subject, sender, unseen status, and/or date. "
                                + "Returns summaries (UID, subject, from, date, read/flagged state) — call "
                                + "read_message with a UID and account to fetch full content. Omit 'account' "
                                + "to search that mailbox across every linked account instead of just one.",
                        JsonSchemas.object(searchProperties(), List.of("folder"))),

                new McpToolDefinition(
                        "read_message",
                        "Fetch the full content (subject, from, to, text/HTML body, attachment metadata) "
                                + "of one message by account, folder, and UID. HTML is sanitized; attachments "
                                + "are metadata only, never auto-fetched.",
                        JsonSchemas.object(
                                Map.of(
                                        "account", accountSchema(true),
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID, from search_messages")),
                                List.of("account", "folder", "uid"))),

                new McpToolDefinition(
                        "move_message",
                        "Move a message from one mailbox to another within the same linked account. "
                                + "Moving a message to a different linked account is not supported.",
                        JsonSchemas.object(
                                Map.of(
                                        "account", accountSchema(true),
                                        "sourceFolder", JsonSchemas.string("Mailbox the message is currently in"),
                                        "uid", JsonSchemas.integer("Message UID"),
                                        "destFolder", JsonSchemas.string("Destination mailbox, in the same account")),
                                List.of("account", "sourceFolder", "uid", "destFolder"))),

                new McpToolDefinition(
                        "mark_message",
                        "Mark a message as read or unread.",
                        JsonSchemas.object(
                                Map.of(
                                        "account", accountSchema(true),
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID"),
                                        "seen", JsonSchemas.bool("true to mark read, false to mark unread")),
                                List.of("account", "folder", "uid", "seen"))),

                new McpToolDefinition(
                        "trash_message",
                        "Move a message to Trash (soft delete) rather than permanently expunging it.",
                        JsonSchemas.object(
                                Map.of(
                                        "account", accountSchema(true),
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID")),
                                List.of("account", "folder", "uid"))));
    }

    private static Map<String, Object> accountSchema(boolean required) {
        return JsonSchemas.string(required
                ? "Linked IMAP account id, from list_accounts."
                : "Linked IMAP account id, from list_accounts. Omit to include every linked account.");
    }

    private static Map<String, Object> searchProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("account", accountSchema(false));
        props.put("folder", JsonSchemas.string("Mailbox to search, e.g. INBOX"));
        props.put("subjectContains", JsonSchemas.string("Only messages whose subject contains this text"));
        props.put("fromContains", JsonSchemas.string("Only messages whose From header contains this text"));
        props.put("unseenOnly", JsonSchemas.bool("Only return unread messages"));
        props.put("sinceIso8601", JsonSchemas.string("Only messages received on/after this ISO-8601 instant, e.g. 2026-07-01T00:00:00Z"));
        props.put("maxResults", JsonSchemas.integer("Maximum number of results (default 50, max 500)"));
        return props;
    }
}
