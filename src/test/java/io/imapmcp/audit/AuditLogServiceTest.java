package io.imapmcp.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    @Test
    void persistsARowWithTheGivenFields() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLogService service = new AuditLogService(repository);
        UUID tenantUserId = UUID.randomUUID();
        UUID imapAccountId = UUID.randomUUID();

        service.record(tenantUserId, imapAccountId, "client-id", "token-hash",
                "read_message", "mcp:mail.read", "INBOX", "success", null, 42);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getTenantUserId()).isEqualTo(tenantUserId);
        assertThat(saved.getImapAccountId()).isEqualTo(imapAccountId);
        assertThat(saved.getOauthClientId()).isEqualTo("client-id");
        assertThat(saved.getTokenIdHash()).isEqualTo("token-hash");
        assertThat(saved.getToolName()).isEqualTo("read_message");
        assertThat(saved.getScopeUsed()).isEqualTo("mcp:mail.read");
        assertThat(saved.getTargetFolder()).isEqualTo("INBOX");
        assertThat(saved.getResultStatus()).isEqualTo("success");
        assertThat(saved.getErrorCode()).isNull();
        assertThat(saved.getLatencyMs()).isEqualTo(42);
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void swallowsARepositoryFailureInsteadOfPropagatingIt() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("db down"));
        AuditLogService service = new AuditLogService(repository);

        assertThatCode(() -> service.record(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "list_mailboxes", "mcp:mail.read", null, "success", null, 5))
                .doesNotThrowAnyException();
    }
}
