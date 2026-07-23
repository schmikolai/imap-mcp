package io.imapmcp.mcp;

/**
 * OAuth scopes an agent can be granted, mapped to the tool groups they
 * unlock (see {@link ToolDispatcher#REQUIRED_SCOPE}). {@code mail.delete} is
 * kept separate from {@code mail.write} so a client can grant "move/mark
 * read" without also granting "delete" — the most destructive action gets
 * its own explicit consent line.
 */
public final class McpScopes {

    public static final String MAIL_READ = "mcp:mail.read";
    public static final String MAIL_WRITE = "mcp:mail.write";
    public static final String MAILBOX_MANAGE = "mcp:mailbox.manage";
    public static final String MAIL_DELETE = "mcp:mail.delete";

    public static final String ALL = MAIL_READ + " " + MAIL_WRITE + " " + MAILBOX_MANAGE + " " + MAIL_DELETE;

    private McpScopes() {
    }
}
