package io.imapmcp.auth;

import io.imapmcp.mcp.McpScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Replaces Spring Authorization Server's built-in default consent page (raw
 * HTML with no CSRF token — served outside Spring MVC/Thymeleaf, so it can
 * never carry one) with a real Thymeleaf-rendered page on the ordinary web
 * security chain, which does get one automatically. Also lets the most
 * destructive scope ({@code mcp:mail.delete}) get its own explicit warning
 * line, per the plan's consent design.
 */
@Controller
public class ConsentController {

    private static final Map<String, String> SCOPE_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        SCOPE_DESCRIPTIONS.put(McpScopes.MAIL_READ, "Search and read your email messages");
        SCOPE_DESCRIPTIONS.put(McpScopes.MAIL_WRITE, "Mark messages read/unread and move them between folders");
        SCOPE_DESCRIPTIONS.put(McpScopes.MAILBOX_MANAGE, "Create new mail folders");
        SCOPE_DESCRIPTIONS.put(McpScopes.MAIL_DELETE, "Permanently delete messages (moves to Trash) — the most destructive permission");
    }

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationConsentService authorizationConsentService;

    public ConsentController(RegisteredClientRepository registeredClientRepository,
                              OAuth2AuthorizationConsentService authorizationConsentService) {
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationConsentService = authorizationConsentService;
    }

    @GetMapping("/oauth2/consent")
    public String consent(Principal principal, Model model,
                           @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                           @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
                           @RequestParam(OAuth2ParameterNames.STATE) String state) {

        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        OAuth2AuthorizationConsent existingConsent =
                authorizationConsentService.findById(registeredClient.getId(), principal.getName());
        Set<String> alreadyApproved = existingConsent != null ? existingConsent.getScopes() : Set.of();

        Set<String> scopesToApprove = new LinkedHashSet<>();
        Set<String> previouslyApproved = new LinkedHashSet<>();
        for (String requestedScope : StringUtils.delimitedListToStringArray(scope, " ")) {
            if (alreadyApproved.contains(requestedScope)) {
                previouslyApproved.add(requestedScope);
            } else {
                scopesToApprove.add(requestedScope);
            }
        }

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", registeredClient.getClientName());
        model.addAttribute("state", state);
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("scopes", describe(scopesToApprove));
        model.addAttribute("previouslyApprovedScopes", describe(previouslyApproved));

        return "consent";
    }

    private Map<String, String> describe(Set<String> scopes) {
        Map<String, String> described = new LinkedHashMap<>();
        for (String scope : scopes) {
            described.put(scope, SCOPE_DESCRIPTIONS.getOrDefault(scope, scope));
        }
        return described;
    }
}
