package io.imapmcp.imap.dto;

public record FolderInfo(String name, boolean canHoldMessages, boolean canHoldFolders, int unreadCount) {
}
