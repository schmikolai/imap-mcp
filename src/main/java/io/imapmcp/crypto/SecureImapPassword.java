package io.imapmcp.crypto;

import javax.security.auth.Destroyable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Holds a decrypted IMAP password for the minimum time needed to open an
 * IMAP session. Backed by a mutable {@code byte[]} (not {@code String}) so
 * the plaintext can be zeroed via {@link #destroy()} as soon as the caller
 * is done with it.
 *
 * <p>Jakarta Mail's {@code Store.connect(host, user, password)} only accepts
 * a {@code String}, so {@link #asTransientString()} necessarily materializes
 * one short-lived, un-zeroable String at the point of IMAP login. Keep the
 * window between calling it and discarding the reference as short as
 * possible; never store the returned value or log it.
 */
public final class SecureImapPassword implements Destroyable, AutoCloseable {

    private final byte[] plaintext;
    private volatile boolean destroyed = false;

    public SecureImapPassword(byte[] plaintext) {
        this.plaintext = plaintext;
    }

    public String asTransientString() {
        checkNotDestroyed();
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    @Override
    public void destroy() {
        Arrays.fill(plaintext, (byte) 0);
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void close() {
        destroy();
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("SecureImapPassword has already been destroyed");
        }
    }

    @Override
    public String toString() {
        return "SecureImapPassword[REDACTED]";
    }
}
