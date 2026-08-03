package io.imapmcp.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per MCP tool invocation (or account-lockout trip), matching
 * {@code audit_log} in V1__init_schema.sql exactly. Write-once — nothing
 * updates a row after insert, so there are no setters beyond construction.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "tenant_user_id")
    private UUID tenantUserId;

    @Column(name = "imap_account_id")
    private UUID imapAccountId;

    @Column(name = "oauth_client_id")
    private String oauthClientId;

    @Column(name = "token_id_hash")
    private String tokenIdHash;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "scope_used")
    private String scopeUsed;

    @Column(name = "target_folder")
    private String targetFolder;

    @Column(name = "result_status", nullable = false)
    private String resultStatus;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    protected AuditLog() {
    }

    public AuditLog(UUID tenantUserId, UUID imapAccountId, String oauthClientId, String tokenIdHash,
                     String toolName, String scopeUsed, String targetFolder, String resultStatus,
                     String errorCode, Integer latencyMs) {
        this.occurredAt = Instant.now();
        this.tenantUserId = tenantUserId;
        this.imapAccountId = imapAccountId;
        this.oauthClientId = oauthClientId;
        this.tokenIdHash = tokenIdHash;
        this.toolName = toolName;
        this.scopeUsed = scopeUsed;
        this.targetFolder = targetFolder;
        this.resultStatus = resultStatus;
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getTenantUserId() {
        return tenantUserId;
    }

    public UUID getImapAccountId() {
        return imapAccountId;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public String getTokenIdHash() {
        return tokenIdHash;
    }

    public String getToolName() {
        return toolName;
    }

    public String getScopeUsed() {
        return scopeUsed;
    }

    public String getTargetFolder() {
        return targetFolder;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }
}
