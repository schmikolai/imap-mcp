package io.imapmcp.mcp;

import io.imapmcp.auth.OAuthProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) — the discovery document
 * MCP clients fetch (via the {@code resource_metadata} hint on a 401's
 * {@code WWW-Authenticate} header, see {@code McpSecurityConfig}) to learn
 * which authorization server issues tokens for {@code /mcp} and which scopes
 * exist, before ever building an {@code /oauth2/authorize} request. Without
 * this, a client has no way to know the {@code mcp:*} scopes and requests
 * none — which the consent flow then treats as an outright denial.
 */
@RestController
public class ProtectedResourceMetadataController {

    private final String issuerUri;

    public ProtectedResourceMetadataController(OAuthProperties properties) {
        this.issuerUri = properties.getIssuerUri();
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", issuerUri + "/mcp");
        metadata.put("authorization_servers", List.of(issuerUri));
        metadata.put("scopes_supported", List.of(
                McpScopes.MAIL_READ, McpScopes.MAIL_WRITE, McpScopes.MAILBOX_MANAGE, McpScopes.MAIL_DELETE));
        metadata.put("bearer_methods_supported", List.of("header"));
        return metadata;
    }
}
