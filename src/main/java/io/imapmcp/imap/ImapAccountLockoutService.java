package io.imapmcp.imap;

import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Tracks IMAP authentication failures per account and applies a cool-down
 * once a threshold is hit. This exists to protect two parties: the user's
 * actual mailbox provider (which may itself lock the account out after too
 * many failed logins) and this service (so it can't be used as a
 * credential-stuffing proxy against upstream IMAP servers).
 */
@Service
public class ImapAccountLockoutService {

    private final ImapAccountRepository imapAccountRepository;
    private final ImapProperties properties;

    public ImapAccountLockoutService(ImapAccountRepository imapAccountRepository, ImapProperties properties) {
        this.imapAccountRepository = imapAccountRepository;
        this.properties = properties;
    }

    public void checkNotLocked(ImapAccount account) {
        if (account.getStatus() == ImapAccount.Status.LOCKED
                && account.getLockedUntil() != null
                && account.getLockedUntil().isAfter(Instant.now())) {
            throw new AccountLockedException(account.getLockedUntil());
        }
    }

    @Transactional
    public void recordFailure(ImapAccount account) {
        int failures = account.getConsecutiveAuthFailures() + 1;
        account.setConsecutiveAuthFailures(failures);
        if (failures >= properties.getLockoutThreshold()) {
            account.setStatus(ImapAccount.Status.LOCKED);
            account.setLockedUntil(Instant.now().plusSeconds(properties.getLockoutBackoffSeconds()));
        }
        imapAccountRepository.save(account);
    }

    @Transactional
    public void recordSuccess(ImapAccount account) {
        account.setConsecutiveAuthFailures(0);
        account.setLockedUntil(null);
        if (account.getStatus() == ImapAccount.Status.LOCKED
                || account.getStatus() == ImapAccount.Status.PENDING_VERIFICATION) {
            account.setStatus(ImapAccount.Status.ACTIVE);
        }
        account.setLastVerifiedAt(Instant.now());
        imapAccountRepository.save(account);
    }

    public static class AccountLockedException extends RuntimeException {
        public AccountLockedException(Instant lockedUntil) {
            super("IMAP account is locked after repeated authentication failures until " + lockedUntil);
        }
    }
}
