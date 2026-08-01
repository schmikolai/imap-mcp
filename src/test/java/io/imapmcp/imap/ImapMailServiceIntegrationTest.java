package io.imapmcp.imap;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.imapmcp.imap.dto.MessageContent;
import io.imapmcp.imap.dto.MessageSummary;
import io.imapmcp.imap.dto.SearchCriteria;
import io.imapmcp.support.TestEncryptionConfig;
import io.imapmcp.tenant.ImapAccount;
import io.imapmcp.tenant.TenantUser;
import io.imapmcp.tenant.TenantUserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real service stack (ImapAccountLinkingService, the
 * connection pool, ImapMailService) end to end against a local GreenMail
 * IMAP server, backed by a real Postgres via Testcontainers.
 *
 * <p><b>One-time local setup required:</b> this test spins up GreenMail with
 * implicit TLS (matching the plan's "always verify certs, even in tests —
 * use a local test CA instead of disabling validation" rule) and generates a
 * throwaway self-signed keystore via the {@code keytool} CLI at
 * {@code @BeforeAll} time, then points both the JVM's keystore (so GreenMail
 * serves it) and truststore (so our IMAP client trusts it via normal chain
 * validation, not a relaxed/disabled check) at that same file. This requires
 * {@code keytool} to be on PATH — it ships with every JDK. Nothing here
 * weakens TLS validation in the production code path (ImapSessionFactory is
 * exercised unmodified).
 */
@Testcontainers
@SpringBootTest
@Import(TestEncryptionConfig.class)
class ImapMailServiceIntegrationTest {

    private static final String TEST_EMAIL = "integration-user@localhost";
    private static final String TEST_USERNAME = "integration-user";
    private static final String TEST_PASSWORD = "test-app-password";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Needed because RateLimitConfig's ProxyManager bean opens a real
    // Lettuce connection at context startup (see its javadoc) — without
    // this, a full @SpringBootTest context load would newly require a
    // manually-started Redis at localhost:6379, breaking "just run
    // ./gradlew test" for anyone who hasn't run `docker compose up -d`.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static GreenMail greenMail;
    private static Path keystorePath;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Flyway connects as a separate, schema-owning role (see CLAUDE.md's
        // "Credential encryption"/multi-tenant notes) rather than reusing
        // spring.datasource.* above, so it needs its own override here too —
        // otherwise it falls back to application.yml's default localhost:5432
        // owner connection and can hit an unrelated Postgres instance instead
        // of this container.
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeAll
    static void startTestImapServer() throws Exception {
        keystorePath = Files.createTempFile("greenmail-test-keystore", ".jks");
        Files.deleteIfExists(keystorePath);

        Process keytool = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "greenmail-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-keystore", keystorePath.toString(),
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        String keytoolOutput = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed to generate test keystore:\n" + keytoolOutput);
        }

        System.setProperty("javax.net.ssl.keyStore", keystorePath.toString());
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");
        System.setProperty("javax.net.ssl.trustStore", keystorePath.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

        // GreenMail's SSL server socket factory doesn't read the standard
        // javax.net.ssl.keyStore property above (that only affects our IMAP
        // client's trust decisions) - it serves its own bundled self-signed
        // cert unless pointed at a different keystore via these properties,
        // so without this it presents a cert our truststore never trusts.
        System.setProperty("greenmail.tls.keystore.file", keystorePath.toString());
        System.setProperty("greenmail.tls.keystore.password", "changeit");
        System.setProperty("greenmail.tls.key.password", "changeit");

        greenMail = new GreenMail(new ServerSetup[]{ServerSetupTest.SMTP, ServerSetupTest.IMAPS});
        greenMail.start();
        greenMail.setUser(TEST_EMAIL, TEST_USERNAME, TEST_PASSWORD);
    }

    @AfterAll
    static void stopTestImapServer() throws Exception {
        if (greenMail != null) {
            greenMail.stop();
        }
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
        System.clearProperty("javax.net.ssl.keyStore");
        System.clearProperty("javax.net.ssl.keyStorePassword");
        System.clearProperty("javax.net.ssl.trustStore");
        System.clearProperty("javax.net.ssl.trustStorePassword");
        System.clearProperty("greenmail.tls.keystore.file");
        System.clearProperty("greenmail.tls.keystore.password");
        System.clearProperty("greenmail.tls.key.password");
    }

    @Autowired
    private TenantUserRepository tenantUserRepository;

    @Autowired
    private ImapAccountLinkingService linkingService;

    @Autowired
    private ImapMailService imapMailService;

    @Test
    void linkAccountThenSearchReadMarkReadAndMoveAMessage() {
        TenantUser tenantUser = tenantUserRepository.save(new TenantUser(TEST_EMAIL, "unused-in-this-test"));

        ImapAccount account = linkingService.linkAccount(tenantUser, new ImapAccountLinkingService.LinkImapAccountRequest(
                "Integration test account",
                "localhost",
                ServerSetupTest.IMAPS.getPort(),
                ImapAccount.TlsMode.IMPLICIT,
                TEST_USERNAME,
                TEST_PASSWORD));
        assertThat(account.getStatus()).isEqualTo(ImapAccount.Status.ACTIVE);

        GreenMailUtil.sendTextEmailTest(
                TEST_EMAIL, "sender@example.com", "Integration test subject", "Integration test body");
        greenMail.waitForIncomingEmail(5_000, 1);

        UUID tenantUserId = tenantUser.getId();
        UUID accountId = account.getId();

        List<MessageSummary> inboxResults = imapMailService.search(
                tenantUserId, accountId, "INBOX", new SearchCriteria(null, null, null, null, null));
        assertThat(inboxResults).hasSize(1);
        assertThat(inboxResults.get(0).seen()).isFalse();
        long uid = inboxResults.get(0).uid();

        MessageContent content = imapMailService.readMessage(tenantUserId, accountId, "INBOX", uid);
        assertThat(content.subject()).isEqualTo("Integration test subject");
        assertThat(content.bodyText()).contains("Integration test body");

        imapMailService.setSeen(tenantUserId, accountId, "INBOX", uid, true);
        List<MessageSummary> unseenAfterMarkingRead = imapMailService.search(
                tenantUserId, accountId, "INBOX", new SearchCriteria(null, null, true, null, null));
        assertThat(unseenAfterMarkingRead).isEmpty();

        imapMailService.createFolder(tenantUserId, accountId, "Archive");
        imapMailService.moveMessage(tenantUserId, accountId, "INBOX", uid, "Archive");

        List<MessageSummary> archived = imapMailService.search(
                tenantUserId, accountId, "Archive", new SearchCriteria(null, null, null, null, null));
        assertThat(archived).hasSize(1);
    }
}
