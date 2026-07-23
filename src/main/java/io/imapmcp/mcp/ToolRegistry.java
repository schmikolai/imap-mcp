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
 * mark read). Each schema is intentionally scoped to a single already-linked
 * IMAP account (see {@link McpPrincipal}) — multi-account selection via tool
 * arguments is deferred until OAuth-scoped grants exist (plan phase 4), so a
 * static bearer token can't be tricked into touching an account it wasn't
 * issued for.
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
                        "list_mailboxes",
                        "List all mailboxes/folders in the connected IMAP account.",
                        JsonSchemas.object(Map.of(), List.of())),

                new McpToolDefinition(
                        "create_mailbox",
                        "Create a new mailbox/folder.",
                        JsonSchemas.object(
                                Map.of("name", JsonSchemas.string("Folder name to create")),
                                List.of("name"))),

                new McpToolDefinition(
                        "search_messages",
                        "Search messages in a mailbox by subject, sender, unseen status, and/or date. "
                                + "Returns summaries (UID, subject, from, date, read/flagged state) — call "
                                + "read_message with a UID to fetch full content.",
                        JsonSchemas.object(searchProperties(), List.of("folder"))),

                new McpToolDefinition(
                        "read_message",
                        "Fetch the full content (subject, from, to, text/HTML body, attachment metadata) "
                                + "of one message by UID. HTML is sanitized; attachments are metadata only, "
                                + "never auto-fetched.",
                        JsonSchemas.object(
                                Map.of(
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID, from search_messages")),
                                List.of("folder", "uid"))),

                new McpToolDefinition(
                        "move_message",
                        "Move a message from one mailbox to another.",
                        JsonSchemas.object(
                                Map.of(
                                        "sourceFolder", JsonSchemas.string("Mailbox the message is currently in"),
                                        "uid", JsonSchemas.integer("Message UID"),
                                        "destFolder", JsonSchemas.string("Destination mailbox")),
                                List.of("sourceFolder", "uid", "destFolder"))),

                new McpToolDefinition(
                        "mark_message",
                        "Mark a message as read or unread.",
                        JsonSchemas.object(
                                Map.of(
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID"),
                                        "seen", JsonSchemas.bool("true to mark read, false to mark unread")),
                                List.of("folder", "uid", "seen"))),

                new McpToolDefinition(
                        "trash_message",
                        "Move a message to Trash (soft delete) rather than permanently expunging it.",
                        JsonSchemas.object(
                                Map.of(
                                        "folder", JsonSchemas.string("Mailbox containing the message"),
                                        "uid", JsonSchemas.integer("Message UID")),
                                List.of("folder", "uid"))));
    }

    private static Map<String, Object> searchProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("folder", JsonSchemas.string("Mailbox to search, e.g. INBOX"));
        props.put("subjectContains", JsonSchemas.string("Only messages whose subject contains this text"));
        props.put("fromContains", JsonSchemas.string("Only messages whose From header contains this text"));
        props.put("unseenOnly", JsonSchemas.bool("Only return unread messages"));
        props.put("sinceIso8601", JsonSchemas.string("Only messages received on/after this ISO-8601 instant, e.g. 2026-07-01T00:00:00Z"));
        props.put("maxResults", JsonSchemas.integer("Maximum number of results (default 50, max 500)"));
        return props;
    }
}
