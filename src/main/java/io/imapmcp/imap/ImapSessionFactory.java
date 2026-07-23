package io.imapmcp.imap;

import io.imapmcp.tenant.ImapAccount;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds and connects a Jakarta Mail {@link Store} for one IMAP account.
 * TLS validation is never relaxed here, including in tests — point
 * integration tests at a locally trusted test CA instead of disabling
 * hostname/certificate checks.
 */
@Component
@EnableConfigurationProperties(ImapProperties.class)
public class ImapSessionFactory {

    private final ImapProperties properties;

    public ImapSessionFactory(ImapProperties properties) {
        this.properties = properties;
    }

    public Store connect(ImapAccount account, String plaintextPassword) throws MessagingException {
        boolean implicitTls = account.getTlsMode() == ImapAccount.TlsMode.IMPLICIT;
        String protocol = implicitTls ? "imaps" : "imap";

        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", account.getHost());
        props.put("mail." + protocol + ".port", String.valueOf(account.getPort()));
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(implicitTls));
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(properties.getConnectTimeoutMs()));
        props.put("mail." + protocol + ".timeout", String.valueOf(properties.getReadTimeoutMs()));

        if (!implicitTls) {
            // STARTTLS mode: fail the connection rather than silently
            // falling back to plaintext if the server doesn't offer it.
            props.put("mail.imap.starttls.enable", "true");
            props.put("mail.imap.starttls.required", "true");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(account.getHost(), account.getPort(), account.getUsername(), plaintextPassword);
        return store;
    }
}
