package io.imapmcp.imap;

import java.util.regex.Pattern;

/**
 * Guards against IMAP command injection via agent-supplied folder names.
 * Jakarta Mail's typed {@code Store.getFolder(name)} API never builds raw
 * protocol commands from this string directly, but a name containing CRLF,
 * NUL, or other control characters could still confuse a lenient server or
 * downstream logging/UI — reject them outright rather than relying solely
 * on the client library's quoting.
 */
public final class FolderNameValidator {

    private static final int MAX_LENGTH = 255;
    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private FolderNameValidator() {
    }

    public static void validate(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new InvalidFolderNameException("Folder name must not be blank");
        }
        if (folderName.length() > MAX_LENGTH) {
            throw new InvalidFolderNameException("Folder name exceeds " + MAX_LENGTH + " characters");
        }
        if (DISALLOWED_CHARS.matcher(folderName).find()) {
            throw new InvalidFolderNameException("Folder name contains disallowed control characters");
        }
    }

    public static class InvalidFolderNameException extends RuntimeException {
        public InvalidFolderNameException(String message) {
            super(message);
        }
    }
}
