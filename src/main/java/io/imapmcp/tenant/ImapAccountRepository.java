package io.imapmcp.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImapAccountRepository extends JpaRepository<ImapAccount, UUID> {

    List<ImapAccount> findByTenantUserId(UUID tenantUserId);

    /**
     * Ownership-checked lookup — every IMAP action must resolve the account
     * through this method (never a bare findById) so a mailbox/account id
     * supplied by an agent can't be used to reach another tenant's account.
     */
    Optional<ImapAccount> findByIdAndTenantUserId(UUID id, UUID tenantUserId);
}
