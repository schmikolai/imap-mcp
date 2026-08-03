package io.imapmcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.imapmcp.audit.AuditLogService;
import io.imapmcp.imap.ImapMailService;
import io.imapmcp.imap.dto.SearchCriteria;
import io.imapmcp.mcp.dto.AccountResult;
import io.imapmcp.mcp.dto.LinkedAccountSummary;
import io.imapmcp.mcp.dto.ToolCallResult;
import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;

/**
 * Executes one {@code tools/call} against {@link ImapMailService}, scoped
 * to the calling principal's tenant. Every failure that originates from the
 * IMAP layer (bad folder name, message not found, account locked out, IMAP
 * protocol error) is caught here and turned into an {@code isError: true}
 * tool result rather than an exception, per MCP convention — see
 * {@link ToolCallResult}.
 *
 * <p>An OAuth grant authorizes a tenant, not a single {@code ImapAccount}
 * (see {@link McpPrincipal}), so most tools accept an {@code account} tool
 * argument to say which linked account they target — see {@link
 * #requireAccount} (single-target tools: identifying a message/folder by
 * UID needs to know which account's UID space it's in, since UIDs aren't
 * unique across accounts) and {@link #withAccounts} (read-only tools that
 * fan out across every linked account when {@code account} is omitted).
 * Every resolution goes through {@link ImapAccountRepository#findByIdAndTenantUserId}
 * so an account id from one tenant can never be used to reach another's.
 */
@Component
public class ToolDispatcher {

    /**
     * Least-privilege scope required per tool — enforced against the
     * access token's granted scopes (as {@code SCOPE_*} authorities) before
     * the tool ever touches IMAP. {@code trash_message} requires the
     * separate, most-destructive {@code mail.delete} scope rather than
     * {@code mail.write}, so an agent can be granted "move/mark read"
     * without also being granted "delete". {@code list_accounts} is
     * deliberately absent — it returns account metadata, not mail, so any
     * valid token may call it regardless of granted scopes.
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
    private final ImapAccountRepository imapAccountRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    public ToolDispatcher(ImapMailService imapMailService, ImapAccountRepository imapAccountRepository,
                           ObjectMapper objectMapper, AuditLogService auditLogService, MeterRegistry meterRegistry) {
        this.imapMailService = imapMailService;
        this.imapAccountRepository = imapAccountRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
    }

    public ToolCallResult call(String toolName, Map<String, Object> arguments, McpPrincipal principal) {
        UUID tenantUserId = principal.tenantUserId();
        UUID accountIdForAudit = optionalString(arguments, "account").map(this::tryParseUuid).orElse(null);
        Instant start = Instant.now();
        String targetFolder = extractTargetFolder(arguments);
        String requiredScope = REQUIRED_SCOPE.get(toolName);

        if (requiredScope != null && !hasScope(requiredScope)) {
            recordOutcome(toolName, tenantUserId, accountIdForAudit, requiredScope, targetFolder,
                    "denied", "insufficient_scope", start);
            return ToolCallResult.error("Insufficient scope: this action requires '" + requiredScope + "'");
        }

        try {
            Object result = switch (toolName) {
                case "list_accounts" -> imapAccountRepository.findByTenantUserIdOrderByCreatedAtAsc(tenantUserId)
                        .stream()
                        .map(LinkedAccountSummary::from)
                        .toList();

                case "list_mailboxes" -> withAccounts(tenantUserId, arguments,
                        account -> imapMailService.listFolders(tenantUserId, account.getId()));

                case "create_mailbox" -> {
                    ImapAccount account = requireAccount(arguments, tenantUserId);
                    imapMailService.createFolder(tenantUserId, account.getId(), requireString(arguments, "name"));
                    yield Map.of("status", "created");
                }

                case "search_messages" -> withAccounts(tenantUserId, arguments,
                        account -> imapMailService.search(tenantUserId, account.getId(),
                                requireString(arguments, "folder"), toSearchCriteria(arguments)));

                case "read_message" -> {
                    ImapAccount account = requireAccount(arguments, tenantUserId);
                    yield imapMailService.readMessage(tenantUserId, account.getId(),
                            requireString(arguments, "folder"), requireLong(arguments, "uid"));
                }

                case "move_message" -> {
                    ImapAccount account = requireAccount(arguments, tenantUserId);
                    imapMailService.moveMessage(
                            tenantUserId, account.getId(),
                            requireString(arguments, "sourceFolder"),
                            requireLong(arguments, "uid"),
                            requireString(arguments, "destFolder"));
                    yield Map.of("status", "moved");
                }

                case "mark_message" -> {
                    ImapAccount account = requireAccount(arguments, tenantUserId);
                    imapMailService.setSeen(
                            tenantUserId, account.getId(),
                            requireString(arguments, "folder"),
                            requireLong(arguments, "uid"),
                            requireBoolean(arguments, "seen"));
                    yield Map.of("status", "updated");
                }

                case "trash_message" -> {
                    ImapAccount account = requireAccount(arguments, tenantUserId);
                    imapMailService.trashMessage(
                            tenantUserId, account.getId(), requireString(arguments, "folder"), requireLong(arguments, "uid"));
                    yield Map.of("status", "trashed");
                }

                default -> throw new UnknownToolException(toolName);
            };
            recordOutcome(toolName, tenantUserId, accountIdForAudit, requiredScope, targetFolder,
                    "success", null, start);
            return ToolCallResult.success(objectMapper, result);
        } catch (UnknownToolException e) {
            throw e;
        } catch (RuntimeException e) {
            recordOutcome(toolName, tenantUserId, accountIdForAudit, requiredScope, targetFolder,
                    "error", e.getClass().getSimpleName(), start);
            return ToolCallResult.error(errorMessage(e));
        }
    }

    /**
     * Resolves the tool's required {@code account} argument to an
     * ownership-checked {@link ImapAccount}. Used by every tool that
     * targets one specific message or folder, where the {@code account} is
     * never optional: a folder+UID pair alone can't tell two different
     * linked accounts' messages apart, so there is no sensible default to
     * fall back to, and moving/acting across accounts is never attempted.
     */
    private ImapAccount requireAccount(Map<String, Object> arguments, UUID tenantUserId) {
        return requireOwnedAccount(requireString(arguments, "account"), tenantUserId);
    }

    /**
     * Runs {@code operation} against either the single account named by an
     * explicit {@code account} argument, or — if omitted — every account
     * the tenant has linked, in a stable order. A per-account failure (e.g.
     * one account locked out) is captured as that account's
     * {@link AccountResult#failed}, not thrown, so it doesn't take down
     * results already fetched from the other accounts.
     */
    private <T> List<AccountResult<T>> withAccounts(UUID tenantUserId, Map<String, Object> arguments,
                                                      Function<ImapAccount, T> operation) {
        List<ImapAccount> accounts = optionalString(arguments, "account")
                .<List<ImapAccount>>map(raw -> List.of(requireOwnedAccount(raw, tenantUserId)))
                .orElseGet(() -> imapAccountRepository.findByTenantUserIdOrderByCreatedAtAsc(tenantUserId));

        return accounts.stream()
                .map(account -> {
                    try {
                        return AccountResult.ok(account.getId(), account.getDisplayName(), operation.apply(account));
                    } catch (RuntimeException e) {
                        return AccountResult.<T>failed(account.getId(), account.getDisplayName(), errorMessage(e));
                    }
                })
                .toList();
    }

    private ImapAccount requireOwnedAccount(String rawAccountId, UUID tenantUserId) {
        UUID accountId = parseAccountId(rawAccountId);
        return imapAccountRepository.findByIdAndTenantUserId(accountId, tenantUserId)
                .orElseThrow(() -> new InvalidToolArgumentsException("Unknown or inaccessible account: " + rawAccountId));
    }

    private UUID parseAccountId(String raw) {
        UUID id = tryParseUuid(raw);
        if (id == null) {
            throw new InvalidToolArgumentsException("Invalid account id: " + raw);
        }
        return id;
    }

    private UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
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
