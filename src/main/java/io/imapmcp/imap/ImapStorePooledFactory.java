package io.imapmcp.imap;

import io.imapmcp.crypto.AssociatedData;
import io.imapmcp.crypto.EncryptionService;
import io.imapmcp.crypto.EnvelopeCiphertext;
import io.imapmcp.crypto.SecureImapPassword;
import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.ImapAccountRepository;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Creates one authenticated {@link Store} per pool checkout. Every account
 * lookup is ownership-checked ({@code findByIdAndTenantUserId}) so a pool
 * key can never be used to open a connection to another tenant's mailbox.
 */
@Component
public class ImapStorePooledFactory extends BaseKeyedPooledObjectFactory<AccountKey, Store> {

    private final ImapAccountRepository imapAccountRepository;
    private final EncryptionService encryptionService;
    private final ImapSessionFactory imapSessionFactory;
    private final ImapAccountLockoutService lockoutService;

    public ImapStorePooledFactory(ImapAccountRepository imapAccountRepository,
                                   EncryptionService encryptionService,
                                   ImapSessionFactory imapSessionFactory,
                                   ImapAccountLockoutService lockoutService) {
        this.imapAccountRepository = imapAccountRepository;
        this.encryptionService = encryptionService;
        this.imapSessionFactory = imapSessionFactory;
        this.lockoutService = lockoutService;
    }

    @Override
    public Store create(AccountKey key) throws Exception {
        ImapAccount account = imapAccountRepository.findByIdAndTenantUserId(key.imapAccountId(), key.tenantUserId())
                .orElseThrow(() -> new NoSuchElementException("No such IMAP account for this tenant"));

        lockoutService.checkNotLocked(account);

        EnvelopeCiphertext ciphertext = new EnvelopeCiphertext(
                account.getEncryptedSecret(), account.getWrappedDek(), account.getKmsKeyId(), account.getKeyVersion());
        byte[] aad = AssociatedData.forImapAccount(key.tenantUserId(), key.imapAccountId());

        try (SecureImapPassword password = encryptionService.decrypt(ciphertext, aad)) {
            try {
                Store store = imapSessionFactory.connect(account, password.asTransientString());
                lockoutService.recordSuccess(account);
                return store;
            } catch (AuthenticationFailedException e) {
                lockoutService.recordFailure(account);
                throw e;
            }
        }
    }

    @Override
    public PooledObject<Store> wrap(Store store) {
        return new DefaultPooledObject<>(store);
    }

    @Override
    public boolean validateObject(AccountKey key, PooledObject<Store> pooledObject) {
        return pooledObject.getObject().isConnected();
    }

    @Override
    public void destroyObject(AccountKey key, PooledObject<Store> pooledObject) throws MessagingException {
        Store store = pooledObject.getObject();
        if (store.isConnected()) {
            store.close();
        }
    }
}
