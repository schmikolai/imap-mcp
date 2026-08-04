package io.imapmcp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Logs the outcome of {@code /oauth2/authorize} and {@code /oauth2/token}
 * requests — client id, grant/response type, and the resulting HTTP
 * outcome — since Spring Authorization Server doesn't log either endpoint
 * itself, and a failed exchange otherwise leaves no trace anywhere in the
 * logs (this is exactly what made a real production "Claude received the
 * code but couldn't connect" failure impossible to diagnose from logs
 * alone).
 *
 * <p>Deliberately never logs the authorization code, PKCE verifier, client
 * secret, access token or refresh token — only the token endpoint's
 * {@code error}/{@code error_description} fields on a failure response,
 * which are diagnostic-by-design per the OAuth2 spec, and the redirect
 * target's host/path on {@code /oauth2/authorize} (never its query string,
 * which carries the auth code on success).
 */
class OAuthActionLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OAuthActionLoggingFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isToken = "/oauth2/token".equals(path);
        boolean isAuthorize = "/oauth2/authorize".equals(path);

        if (!isToken && !isAuthorize) {
            chain.doFilter(request, response);
            return;
        }

        String clientId = resolveClientId(request);

        if (isToken) {
            String grantType = request.getParameter("grant_type");
            ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
            try {
                chain.doFilter(request, wrappedResponse);
            } finally {
                int status = wrappedResponse.getStatus();
                if (status >= 200 && status < 300) {
                    log.info("oauth token exchange succeeded: client_id={} grant_type={}", clientId, grantType);
                } else {
                    log.warn("oauth token exchange failed: client_id={} grant_type={} status={} {}",
                            clientId, grantType, status, describeOAuthError(wrappedResponse));
                }
                wrappedResponse.copyBodyToResponse();
            }
        } else {
            String responseType = request.getParameter("response_type");
            chain.doFilter(request, response);
            log.info("oauth authorize request: client_id={} response_type={} status={} outcome={}",
                    clientId, responseType, response.getStatus(), describeAuthorizeOutcome(response));
        }
    }

    private String resolveClientId(HttpServletRequest request) {
        String clientId = request.getParameter("client_id");
        if (clientId != null) {
            return clientId;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                return colon >= 0 ? decoded.substring(0, colon) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private String describeOAuthError(ContentCachingResponseWrapper response) {
        byte[] body = response.getContentAsByteArray();
        if (body.length == 0) {
            return "(no response body)";
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            String error = node.path("error").asText(null);
            if (error == null) {
                return "(non-error response body)";
            }
            String description = node.path("error_description").asText(null);
            return description != null
                    ? "error=" + error + " error_description=" + description
                    : "error=" + error;
        } catch (IOException e) {
            return "(unparseable response body)";
        }
    }

    private String describeAuthorizeOutcome(HttpServletResponse response) {
        int status = response.getStatus();
        if (status < 300 || status >= 400) {
            return "http_" + status;
        }
        String location = response.getHeader("Location");
        if (location == null) {
            return "redirect_no_location";
        }
        try {
            URI uri = URI.create(location);
            String path = uri.getPath();
            if (path != null && path.contains("/oauth2/consent")) {
                return "redirect_to_consent";
            }
            if (location.contains("error=")) {
                return "redirect_denied_or_error(host=" + uri.getHost() + ")";
            }
            return "redirect_to_client(host=" + uri.getHost() + ")";
        } catch (IllegalArgumentException e) {
            return "redirect_unparseable_location";
        }
    }
}
