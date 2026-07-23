package io.imapmcp.mcp;

public class UnknownToolException extends RuntimeException {
    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
    }
}
