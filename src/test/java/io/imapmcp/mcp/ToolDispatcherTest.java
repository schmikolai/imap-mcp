package io.imapmcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.imapmcp.audit.AuditLogService;
import io.imapmcp.imap.ImapMailService;
import io.imapmcp.mcp.dto.ToolCallResult;
import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolDispatcherTest {

    private final ImapMailService imapMailService = mock(ImapMailService.class);
    private final ImapAccountRepository imapAccountRepository = mock(ImapAccountRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ToolDispatcher dispatcher = new ToolDispatcher(
            imapMailService, imapAccountRepository, new ObjectMapper(), auditLogService, new SimpleMeterRegistry());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsASuccessAuditRowAndReturnsSuccessResultWhenFanningOutOverAllAccounts() {
        UUID tenantUserId = UUID.randomUUID();
        UUID imapAccountId = UUID.randomUUID();
        ImapAccount account = ownedAccount(imapAccountId);
        authenticateWithScope(McpScopes.MAIL_READ);
        when(imapAccountRepository.findByTenantUserIdOrderByCreatedAtAsc(tenantUserId))
                .thenReturn(java.util.List.of(account));
        when(imapMailService.listFolders(tenantUserId, imapAccountId)).thenReturn(java.util.List.of());

        ToolCallResult result = dispatcher.call("list_mailboxes", Map.of(), new McpPrincipal(tenantUserId));

        assertThat(result.isError()).isFalse();
        verify(auditLogService).record(eq(tenantUserId), isNull(), isNull(), isNull(),
                eq("list_mailboxes"), eq(McpScopes.MAIL_READ), isNull(), eq("success"), isNull(), any());
    }

    @Test
    void recordsAnErrorAuditRowWhenTheImapLayerThrows() {
        UUID tenantUserId = UUID.randomUUID();
        UUID imapAccountId = UUID.randomUUID();
        ImapAccount account = ownedAccount(imapAccountId);
        authenticateWithScope(McpScopes.MAIL_READ);
        when(imapAccountRepository.findByIdAndTenantUserId(imapAccountId, tenantUserId))
                .thenReturn(Optional.of(account));
        when(imapMailService.readMessage(eq(tenantUserId), eq(imapAccountId), eq("INBOX"), eq(1L)))
                .thenThrow(new NoSuchElementException("No message with UID 1"));

        ToolCallResult result = dispatcher.call("read_message",
                Map.of("account", imapAccountId.toString(), "folder", "INBOX", "uid", 1),
                new McpPrincipal(tenantUserId));

        assertThat(result.isError()).isTrue();
        verify(auditLogService).record(eq(tenantUserId), eq(imapAccountId), isNull(), isNull(),
                eq("read_message"), eq(McpScopes.MAIL_READ), eq("INBOX"), eq("error"),
                eq("NoSuchElementException"), any());
    }

    @Test
    void recordsADeniedAuditRowWhenScopeIsMissing() {
        UUID tenantUserId = UUID.randomUUID();
        UUID imapAccountId = UUID.randomUUID();
        authenticateWithScope(McpScopes.MAIL_READ);

        ToolCallResult result = dispatcher.call("trash_message",
                Map.of("account", imapAccountId.toString(), "folder", "INBOX", "uid", 1),
                new McpPrincipal(tenantUserId));

        assertThat(result.isError()).isTrue();
        verify(auditLogService).record(eq(tenantUserId), eq(imapAccountId), isNull(), isNull(),
                eq("trash_message"), eq(McpScopes.MAIL_DELETE), eq("INBOX"), eq("denied"),
                eq("insufficient_scope"), any());
    }

    @Test
    void requiresAccountForSingleTargetTools() {
        UUID tenantUserId = UUID.randomUUID();
        authenticateWithScope(McpScopes.MAIL_READ);

        ToolCallResult result = dispatcher.call("read_message", Map.of("folder", "INBOX", "uid", 1),
                new McpPrincipal(tenantUserId));

        assertThat(result.isError()).isTrue();
    }

    private ImapAccount ownedAccount(UUID id) {
        ImapAccount account = mock(ImapAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getDisplayName()).thenReturn("work");
        return account;
    }

    private void authenticateWithScope(String scope) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("agent", null, java.util.List.of(new SimpleGrantedAuthority("SCOPE_" + scope))));
    }
}
