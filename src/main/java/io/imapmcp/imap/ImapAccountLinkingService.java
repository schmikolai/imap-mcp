package io.imapmcp.imap;

import io.imapmcp.crypto.AssociatedData;
import io.imapmcp.crypto.EncryptionService;
import io.imapmcp.crypto.EnvelopeCiphertext;
import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
import io.imapmcp.tenant.TenantUser;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Onboards a new IMAP account: persists it envelope-encrypted, then
 * immediately attempts a real IMAP login (outside the pool, so the caller
 * gets synchronous pass/fail feedback) before marking it ACTIVE. An account
 * that fails verification is kept in PENDING_VERIFICATION / NEEDS_REAUTH
 * rather than silently trusted.
 */
@Service
public class ImapAccountLinkingService {

    private final ImapAccountRepository imapAccountRepository;
    private final EncryptionService encryptionService;
    private final ImapSessionFactory imapSessionFactory;

    public ImapAccountLinkingService(ImapAccountRepository imapAccountRepository,
                                      EncryptionService encryptionService,
                                      ImapSessionFactory imapSessionFactory) {
        this.imapAccountRepository = imapAccountRepository;
        this.encryptionService = encryptionService;
        this.imapSessionFactory = imapSessionFactory;
    }

    /**
     * Deliberately NOT {@code @Transactional}: each {@code save()} below must
     * commit independently so a failed test-connect still leaves the account
     * row persisted as {@code NEEDS_REAUTH} (for retry/audit) instead of the
     * whole insert being rolled back by the {@link LinkingFailedException}
     * that {@link #verifyConnection} throws.
     */
    public ImapAccount linkAccount(TenantUser tenantUser, LinkImapAccountRequest request) {
        ImapAccount account = new ImapAccount(
                tenantUser,
                request.displayName(),
                request.host(),
                request.port(),
                request.tlsMode(),
                request.username());

        // account.getId() is assigned client-side (see ImapAccount), so the
        // AAD binding and the encrypted secret can both be computed before
        // the row is ever inserted — encrypted_secret/wrapped_dek are
        // NOT NULL, so an initial "bare" insert followed by an update would
        // violate that constraint on the first insert.
        byte[] aad = AssociatedData.forImapAccount(tenantUser.getId(), account.getId());
        EnvelopeCiphertext ciphertext = encryptionService.encrypt(
                request.password().getBytes(StandardCharsets.UTF_8), aad);
        account.applyEncryptedSecret(
                ciphertext.packedCiphertext(), ciphertext.wrappedDek(), ciphertext.kmsKeyId(), ciphertext.keyVersion());

        account = imapAccountRepository.save(account);

        verifyConnection(account, request.password());
        return account;
    }

    private void verifyConnection(ImapAccount account, String plaintextPassword) {
        try {
            Store store = imapSessionFactory.connect(account, plaintextPassword);
            store.close();
            account.setStatus(ImapAccount.Status.ACTIVE);
            account.setConsecutiveAuthFailures(0);
            account.setLastVerifiedAt(Instant.now());
        } catch (MessagingException e) {
            account.setStatus(ImapAccount.Status.NEEDS_REAUTH);
            imapAccountRepository.save(account);
            throw new LinkingFailedException("Could not verify IMAP login with the provided credentials", e);
        }
        imapAccountRepository.save(account);
    }

    public record LinkImapAccountRequest(
            String displayName,
            String host,
            int port,
            ImapAccount.TlsMode tlsMode,
            String username,
            String password) {
    }

    public static class LinkingFailedException extends RuntimeException {
        public LinkingFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
