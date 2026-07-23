package io.imapmcp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Authorization Server config: a fixed issuer (required so the resource
 * server validating {@code /mcp/**} tokens and the AS issuing them agree on
 * the {@code iss} claim), plus an optional one-time static client to seed —
 * the "vetted allow-list" from plan phase 4. Leave seed-client fields blank
 * to skip seeding (e.g. once you're managing clients directly in the DB).
 */
@ConfigurationProperties(prefix = "imapmcp.oauth")
public class OAuthProperties {

    private String issuerUri;
    private SeedClient seedClient = new SeedClient();

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public SeedClient getSeedClient() {
        return seedClient;
    }

    public void setSeedClient(SeedClient seedClient) {
        this.seedClient = seedClient;
    }

    public static class SeedClient {
        private String clientId;
        private String clientSecret;
        private String displayName = "Development test client";
        private List<String> redirectUris = List.of();

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getRedirectUris() {
            return redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris;
        }
    }
}
