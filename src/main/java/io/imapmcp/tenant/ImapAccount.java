package io.imapmcp.tenant;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "imap_account")
public class ImapAccount {

    public enum TlsMode { IMPLICIT, STARTTLS }

    public enum Status { PENDING_VERIFICATION, ACTIVE, LOCKED, NEEDS_REAUTH }

    // Assigned (not @GeneratedValue) so the AAD binding the encrypted secret
    // to this exact row (see AssociatedData.forImapAccount) can be computed
    // BEFORE the first insert — the encrypted_secret/wrapped_dek columns are
    // NOT NULL, so linking can't do "insert bare row, then encrypt, then
    // update" without violating that constraint on the first insert.
    @Id
    private UUID id;

    // EAGER, not LAZY: nothing in the app actually navigates this
    // association (everything resolves TenantUser by id directly), so
    // eager loading costs nothing and skips the lazy-proxy machinery
    // entirely.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tenant_user_id", nullable = false)
    private TenantUser tenantUser;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(name = "tls_mode", nullable = false, length = 16)
    private TlsMode tlsMode = TlsMode.IMPLICIT;

    @Column(nullable = false, length = 320)
    private String username;

    // Deliberately no @Lob: on Hibernate 6 + PostgreSQL, @Lob byte[] can map
    // to the `oid` large-object type instead of `bytea`. Plain byte[] uses
    // Hibernate's default VARBINARY mapping, which matches the migration's
    // BYTEA columns.
    @Column(name = "encrypted_secret", nullable = false)
    private byte[] encryptedSecret;

    @Column(name = "wrapped_dek", nullable = false)
    private byte[] wrappedDek;

    @Column(name = "kms_key_id", nullable = false)
    private String kmsKeyId;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "algorithm_id", nullable = false, length = 64)
    private String algorithmId = "AES256_GCM";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.PENDING_VERIFICATION;

    @Column(name = "consecutive_auth_failures", nullable = false)
    private int consecutiveAuthFailures = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImapAccount() {
    }

    public ImapAccount(TenantUser tenantUser, String displayName, String host, int port,
                        TlsMode tlsMode, String username) {
        this.id = UUID.randomUUID();
        this.tenantUser = tenantUser;
        this.displayName = displayName;
        this.host = host;
        this.port = port;
        this.tlsMode = tlsMode;
        this.username = username;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public TenantUser getTenantUser() {
        return tenantUser;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public TlsMode getTlsMode() {
        return tlsMode;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getEncryptedSecret() {
        return encryptedSecret;
    }

    public byte[] getWrappedDek() {
        return wrappedDek;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getConsecutiveAuthFailures() {
        return consecutiveAuthFailures;
    }

    public void setConsecutiveAuthFailures(int consecutiveAuthFailures) {
        this.consecutiveAuthFailures = consecutiveAuthFailures;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public void applyEncryptedSecret(byte[] encryptedSecret, byte[] wrappedDek, String kmsKeyId, int keyVersion) {
        this.encryptedSecret = encryptedSecret;
        this.wrappedDek = wrappedDek;
        this.kmsKeyId = kmsKeyId;
        this.keyVersion = keyVersion;
    }
}
