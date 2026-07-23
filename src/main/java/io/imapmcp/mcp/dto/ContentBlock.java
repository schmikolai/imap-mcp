package io.imapmcp.mcp.dto;

public record ContentBlock(String type, String text) {
    public static ContentBlock text(String text) {
        return new ContentBlock("text", text);
    }
}
