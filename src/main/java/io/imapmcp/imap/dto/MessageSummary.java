package io.imapmcp.imap.dto;

import java.time.Instant;

public record MessageSummary(long uid, String subject, String from, Instant sentDate, boolean seen, boolean flagged) {
}
