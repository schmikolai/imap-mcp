package io.imapmcp.imap;

import io.imapmcp.imap.dto.FolderInfo;
import io.imapmcp.imap.dto.MessageContent;
import io.imapmcp.imap.dto.MessageSummary;
import io.imapmcp.imap.dto.SearchCriteria;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The six MCP tool actions against a user's IMAP mailbox. Every method takes
 * the authenticated tenantUserId explicitly and resolves the account through
 * the ownership-checked pool key — an agent-supplied accountId or folder
 * name can never reach another tenant's mailbox (see
 * {@link ImapStorePooledFactory#create}).
 */
@Service
public class ImapMailService {

    private static final String DEFAULT_TRASH_FOLDER = "Trash";

    private final ImapConnectionPool connectionPool;
    private final MimeContentExtractor mimeContentExtractor;

    public ImapMailService(ImapConnectionPool connectionPool, MimeContentExtractor mimeContentExtractor) {
        this.connectionPool = connectionPool;
        this.mimeContentExtractor = mimeContentExtractor;
    }

    public List<FolderInfo> listFolders(UUID tenantUserId, UUID imapAccountId) {
        return withStore(tenantUserId, imapAccountId, store -> {
            List<FolderInfo> result = new ArrayList<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                result.add(new FolderInfo(
                        folder.getFullName(),
                        (folder.getType() & Folder.HOLDS_MESSAGES) != 0,
                        (folder.getType() & Folder.HOLDS_FOLDERS) != 0,
                        folder.getUnreadMessageCount()));
            }
            return result;
        });
    }

    public void createFolder(UUID tenantUserId, UUID imapAccountId, String folderName) {
        FolderNameValidator.validate(folderName);
        withStore(tenantUserId, imapAccountId, store -> {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists() && !folder.create(Folder.HOLDS_MESSAGES)) {
                throw new IllegalStateException("Failed to create folder: " + folderName);
            }
            return null;
        });
    }

    public List<MessageSummary> search(UUID tenantUserId, UUID imapAccountId, String folderName, SearchCriteria criteria) {
        FolderNameValidator.validate(folderName);
        return withStore(tenantUserId, imapAccountId, store -> {
            Folder folder = openFolder(store, folderName, Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                SearchTerm term = buildSearchTerm(criteria);
                Message[] messages = term != null ? folder.search(term) : folder.getMessages();
                List<MessageSummary> summaries = new ArrayList<>();
                int limit = criteria.effectiveMaxResults();
                for (int i = messages.length - 1; i >= 0 && summaries.size() < limit; i--) {
                    Message message = messages[i];
                    summaries.add(new MessageSummary(
                            uidFolder.getUID(message),
                            nullToEmpty(message.getSubject()),
                            fromAddress(message),
                            toInstant(message.getSentDate()),
                            message.isSet(Flags.Flag.SEEN),
                            message.isSet(Flags.Flag.FLAGGED)));
                }
                return summaries;
            } finally {
                closeQuietly(folder);
            }
        });
    }

    public MessageContent readMessage(UUID tenantUserId, UUID imapAccountId, String folderName, long uid) {
        FolderNameValidator.validate(folderName);
        return withStore(tenantUserId, imapAccountId, store -> {
            Folder folder = openFolder(store, folderName, Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                Message message = uidFolder.getMessageByUID(uid);
                if (message == null) {
                    throw new NoSuchElementException("No message with UID " + uid + " in " + folderName);
                }
                MimeContentExtractor.Extracted extracted;
                try {
                    extracted = mimeContentExtractor.extract((MimeMessage) message);
                } catch (MessagingException | java.io.IOException e) {
                    throw new ImapOperationException("Failed to read message", e);
                }
                return new MessageContent(
                        uid,
                        nullToEmpty(message.getSubject()),
                        fromAddress(message),
                        toAddresses(message),
                        toInstant(message.getSentDate()),
                        extracted.bodyText(),
                        extracted.bodyHtmlSanitized(),
                        extracted.attachments());
            } finally {
                closeQuietly(folder);
            }
        });
    }

    public void moveMessage(UUID tenantUserId, UUID imapAccountId, String sourceFolder, long uid, String destFolder) {
        FolderNameValidator.validate(sourceFolder);
        FolderNameValidator.validate(destFolder);
        withStore(tenantUserId, imapAccountId, store -> {
            Folder source = openFolder(store, sourceFolder, Folder.READ_WRITE);
            try {
                UIDFolder uidFolder = (UIDFolder) source;
                Message message = uidFolder.getMessageByUID(uid);
                if (message == null) {
                    throw new NoSuchElementException("No message with UID " + uid + " in " + sourceFolder);
                }
                Folder destination = store.getFolder(destFolder);
                if (!destination.exists()) {
                    throw new NoSuchElementException("Destination folder does not exist: " + destFolder);
                }
                // Portable copy+delete rather than the provider-specific IMAP
                // MOVE extension, so this works against any IMAP server.
                source.copyMessages(new Message[]{message}, destination);
                message.setFlag(Flags.Flag.DELETED, true);
                return null;
            } finally {
                closeFolder(source, true);
            }
        });
    }

    public void trashMessage(UUID tenantUserId, UUID imapAccountId, String folderName, long uid) {
        moveMessage(tenantUserId, imapAccountId, folderName, uid, DEFAULT_TRASH_FOLDER);
    }

    public void setSeen(UUID tenantUserId, UUID imapAccountId, String folderName, long uid, boolean seen) {
        FolderNameValidator.validate(folderName);
        withStore(tenantUserId, imapAccountId, store -> {
            Folder folder = openFolder(store, folderName, Folder.READ_WRITE);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                Message message = uidFolder.getMessageByUID(uid);
                if (message == null) {
                    throw new NoSuchElementException("No message with UID " + uid + " in " + folderName);
                }
                message.setFlag(Flags.Flag.SEEN, seen);
                return null;
            } finally {
                closeFolder(folder, false);
            }
        });
    }

    private SearchTerm buildSearchTerm(SearchCriteria criteria) {
        List<SearchTerm> terms = new ArrayList<>();
        if (criteria.subjectContains() != null && !criteria.subjectContains().isBlank()) {
            terms.add(new SubjectTerm(criteria.subjectContains()));
        }
        if (criteria.fromContains() != null && !criteria.fromContains().isBlank()) {
            terms.add(new FromStringTerm(criteria.fromContains()));
        }
        if (Boolean.TRUE.equals(criteria.unseenOnly())) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        }
        if (criteria.since() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(criteria.since())));
        }
        if (terms.isEmpty()) {
            return null;
        }
        return terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    private Folder openFolder(Store store, String folderName, int mode) {
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                throw new NoSuchElementException("No such folder: " + folderName);
            }
            folder.open(mode);
            return folder;
        } catch (MessagingException e) {
            throw new ImapOperationException("Failed to open folder " + folderName, e);
        }
    }

    private void closeFolder(Folder folder, boolean expunge) {
        try {
            if (folder.isOpen()) {
                folder.close(expunge);
            }
        } catch (MessagingException ignored) {
            // best effort
        }
    }

    private void closeQuietly(Folder folder) {
        closeFolder(folder, false);
    }

    private String fromAddress(Message message) throws MessagingException {
        jakarta.mail.Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return "";
        }
        return from[0] instanceof InternetAddress internetAddress ? internetAddress.toUnicodeString() : from[0].toString();
    }

    private List<String> toAddresses(Message message) throws MessagingException {
        jakarta.mail.Address[] to = message.getRecipients(Message.RecipientType.TO);
        List<String> result = new ArrayList<>();
        if (to != null) {
            for (jakarta.mail.Address address : to) {
                result.add(address instanceof InternetAddress internetAddress ? internetAddress.toUnicodeString() : address.toString());
            }
        }
        return result;
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> T withStore(UUID tenantUserId, UUID imapAccountId, StoreCallback<T> callback) {
        AccountKey key = new AccountKey(tenantUserId, imapAccountId);
        Store store;
        try {
            store = connectionPool.borrow(key);
        } catch (Exception e) {
            throw new ImapOperationException("Failed to obtain IMAP connection", e);
        }

        boolean healthy = true;
        try {
            return callback.call(store);
        } catch (MessagingException e) {
            healthy = false;
            throw new ImapOperationException("IMAP operation failed", e);
        } catch (RuntimeException e) {
            healthy = false;
            throw e;
        } finally {
            if (healthy) {
                connectionPool.returnStore(key, store);
            } else {
                connectionPool.invalidate(key, store);
            }
        }
    }

    @FunctionalInterface
    private interface StoreCallback<T> {
        T call(Store store) throws MessagingException;
    }

    public static class ImapOperationException extends RuntimeException {
        public ImapOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
