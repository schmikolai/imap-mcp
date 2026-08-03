package io.imapmcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persists {@link AuditLog} rows. A write failure here must never break the
 * tool call (or lockout) it's recording — callers get a best-effort audit
 * trail, not a hard dependency, so failures are logged and swallowed rather
 * than propagated.
 */
@Component
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID tenantUserId, UUID imapAccountId, String oauthClientId, String tokenIdHash,
                        String toolName, String scopeUsed, String targetFolder, String resultStatus,
                        String errorCode, Integer latencyMs) {
        try {
            repository.save(new AuditLog(tenantUserId, imapAccountId, oauthClientId, tokenIdHash,
                    toolName, scopeUsed, targetFolder, resultStatus, errorCode, latencyMs));
        } catch (RuntimeException e) {
            log.warn("Failed to persist audit_log row for tool '{}' (tenant {})", toolName, tenantUserId, e);
        }
    }
}
