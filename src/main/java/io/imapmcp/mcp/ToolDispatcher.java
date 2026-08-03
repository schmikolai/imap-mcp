package io.imapmcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.imapmcp.audit.AuditLogService;
import io.imapmcp.imap.ImapMailService;
import io.imapmcp.imap.dto.SearchCriteria;
import io.imapmcp.mcp.dto.ToolCallResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * Executes one {@code tools/call} against {@link ImapMailService}, scoped
 * to the calling principal's tenant + IMAP account. Every failure that
 * originates from the IMAP layer (bad folder name, message not found,
 * account locked out, IMAP protocol error) is caught here and turned into
 * an {@code isError: true} tool result rather than an exception, per MCP
 * convention — see {@link ToolCallResult}.
 */
@Component
public class ToolDispatcher {

    /**
     * Least-privilege scope required per tool — enforced against the
     * access token's granted scopes (as {@code SCOPE_*} authorities) before
     * the tool ever touches IMAP. {@code trash_message} requires the
     * separate, most-destructive {@code mail.delete} scope rather than
     * {@code mail.write}, so an agent can be granted "move/mark read"
     * without also being granted "delete".
     */
    private static final Map<String, String> REQUIRED_SCOPE = Map.of(
            "list_mailboxes", McpScopes.MAIL_READ,
            "search_messages", McpScopes.MAIL_READ,
            "read_message", McpScopes.MAIL_READ,
            "create_mailbox", McpScopes.MAILBOX_MANAGE,
            "move_message", McpScopes.MAIL_WRITE,
            "mark_message", McpScopes.MAIL_WRITE,
            "trash_message", McpScopes.MAIL_DELETE);

    private final ImapMailService imapMailService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    public ToolDispatcher(ImapMailService imapMailService, ObjectMapper objectMapper,
                           AuditLogService auditLogService, MeterRegistry meterRegistry) {
        this.imapMailService = imapMailService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
    }

    public ToolCallResult call(String toolName, Map<String, Object> arguments, McpPrincipal principal) {
        UUID tenantUserId = principal.tenantUserId();
        UUID imapAccountId = principal.imapAccountId();
        Instant start = Instant.now();
        String targetFolder = extractTargetFolder(arguments);
        String requiredScope = REQUIRED_SCOPE.get(toolName);

        if (requiredScope != null && !hasScope(requiredScope)) {
            recordOutcome(toolName, tenantUserId, imapAccountId, requiredScope, targetFolder,
                    "denied", "insufficient_scope", start);
            return ToolCallResult.error("Insufficient scope: this action requires '" + requiredScope + "'");
        }

        try {
            Object result = switch (toolName) {
                case "list_mailboxes" -> imapMailService.listFolders(tenantUserId, imapAccountId);

                case "create_mailbox" -> {
                    imapMailService.createFolder(tenantUserId, imapAccountId, requireString(arguments, "name"));
                    yield Map.of("status", "created");
                }

                case "search_messages" -> imapMailService.search(
                        tenantUserId, imapAccountId, requireString(arguments, "folder"), toSearchCriteria(arguments));

                case "read_message" -> imapMailService.readMessage(
                        tenantUserId, imapAccountId, requireString(arguments, "folder"), requireLong(arguments, "uid"));

                case "move_message" -> {
                    imapMailService.moveMessage(
                            tenantUserId, imapAccountId,
                            requireString(arguments, "sourceFolder"),
                            requireLong(arguments, "uid"),
                            requireString(arguments, "destFolder"));
                    yield Map.of("status", "moved");
                }

                case "mark_message" -> {
                    imapMailService.setSeen(
                            tenantUserId, imapAccountId,
                            requireString(arguments, "folder"),
                            requireLong(arguments, "uid"),
                            requireBoolean(arguments, "seen"));
                    yield Map.of("status", "updated");
                }

                case "trash_message" -> {
                    imapMailService.trashMessage(
                            tenantUserId, imapAccountId, requireString(arguments, "folder"), requireLong(arguments, "uid"));
                    yield Map.of("status", "trashed");
                }

                default -> throw new UnknownToolException(toolName);
            };
            recordOutcome(toolName, tenantUserId, imapAccountId, requiredScope, targetFolder,
                    "success", null, start);
            return ToolCallResult.success(objectMapper, result);
        } catch (UnknownToolException e) {
            throw e;
        } catch (RuntimeException e) {
            recordOutcome(toolName, tenantUserId, imapAccountId, requiredScope, targetFolder,
                    "error", e.getClass().getSimpleName(), start);
            return ToolCallResult.error(errorMessage(e));
        }
    }

    private void recordOutcome(String toolName, UUID tenantUserId, UUID imapAccountId, String scopeUsed,
                                String targetFolder, String resultStatus, String errorCode, Instant start) {
        Duration elapsed = Duration.between(start, Instant.now());
        meterRegistry.timer("mcp.tool.calls", "tool", toolName, "result", resultStatus).record(elapsed);
        int latencyMs = (int) elapsed.toMillis();

        Jwt jwt = currentJwt();
        String oauthClientId = jwt != null ? jwt.getClaimAsString("client_id") : null;
        String tokenIdHash = jwt != null && jwt.getId() != null ? sha256Hex(jwt.getId()) : null;

        auditLogService.record(tenantUserId, imapAccountId, oauthClientId, tokenIdHash,
                toolName, scopeUsed, targetFolder, resultStatus, errorCode, latencyMs);
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof McpAuthenticationToken mcpAuth
                && mcpAuth.getCredentials() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String extractTargetFolder(Map<String, Object> arguments) {
        return optionalString(arguments, "folder")
                .or(() -> optionalString(arguments, "sourceFolder"))
                .orElse(null);
    }

    private boolean hasScope(String scope) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        String authority = "SCOPE_" + scope;
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private String errorMessage(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private SearchCriteria toSearchCriteria(Map<String, Object> arguments) {
        return new SearchCriteria(
                optionalString(arguments, "subjectContains").orElse(null),
                optionalString(arguments, "fromContains").orElse(null),
                optionalBoolean(arguments, "unseenOnly").orElse(null),
                optionalString(arguments, "sinceIso8601").map(Instant::parse).orElse(null),
                optionalInt(arguments, "maxResults").orElse(null));
    }

    private String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new InvalidToolArgumentsException("Missing or invalid required argument: " + key);
        }
        return s;
    }

    private long requireLong(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new InvalidToolArgumentsException("Missing or invalid required argument: " + key);
    }

    private boolean requireBoolean(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new InvalidToolArgumentsException("Missing or invalid required argument: " + key);
    }

    private Optional<String> optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return (value instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    private Optional<Boolean> optionalBoolean(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    private Optional<Integer> optionalInt(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }
}
