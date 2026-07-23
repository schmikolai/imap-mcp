package io.imapmcp.imap.dto;

public record AttachmentMeta(String filename, String contentType, long approxSizeBytes) {
}
