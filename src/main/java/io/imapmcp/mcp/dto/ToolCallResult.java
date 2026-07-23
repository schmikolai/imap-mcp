package io.imapmcp.mcp.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Per the MCP spec, tool execution failures (bad folder name, message not
 * found, IMAP auth failure, ...) are reported here via {@code isError: true}
 * rather than as a JSON-RPC protocol-level error, so the calling agent can
 * see and react to them. Only malformed requests (unknown tool, unparsable
 * arguments) become protocol-level errors — see McpController.
 */
public record ToolCallResult(List<ContentBlock> content, boolean isError) {

    public static ToolCallResult success(ObjectMapper mapper, Object result) {
        return new ToolCallResult(List.of(ContentBlock.text(toJson(mapper, result))), false);
    }

    public static ToolCallResult error(String message) {
        return new ToolCallResult(List.of(ContentBlock.text(message)), true);
    }

    private static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }
}
