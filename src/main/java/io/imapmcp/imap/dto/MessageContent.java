package io.imapmcp.imap.dto;

import java.time.Instant;
import java.util.List;

/**
 * bodyText / bodyHtmlSanitized are returned as clearly separate, structured
 * fields rather than pre-rendered or inlined into anything instruction-
 * shaped — callers (MCP tool responses) must treat this content as
 * untrusted data, not as instructions to the agent. bodyHtmlSanitized has
 * already had scripts, event handlers and remote resource references
 * stripped (see MimeContentExtractor).
 */
public record MessageContent(
        long uid,
        String subject,
        String from,
        List<String> to,
        Instant sentDate,
        String bodyText,
        String bodyHtmlSanitized,
        List<AttachmentMeta> attachments) {
}
