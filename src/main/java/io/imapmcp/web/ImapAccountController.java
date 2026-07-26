package io.imapmcp.web;

import io.imapmcp.imap.ImapAccountLinkingService;
import io.imapmcp.tenant.ImapAccountRepository;
import io.imapmcp.tenant.TenantUser;
import io.imapmcp.tenant.TenantUserRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class ImapAccountController {

    private final ImapAccountLinkingService linkingService;
    private final ImapAccountRepository imapAccountRepository;
    private final TenantUserRepository tenantUserRepository;

    public ImapAccountController(ImapAccountLinkingService linkingService,
                                  ImapAccountRepository imapAccountRepository,
                                  TenantUserRepository tenantUserRepository) {
        this.linkingService = linkingService;
        this.imapAccountRepository = imapAccountRepository;
        this.tenantUserRepository = tenantUserRepository;
    }

    @GetMapping("/accounts")
    public String listAccounts(Authentication authentication, Model model) {
        TenantUser tenantUser = currentTenantUser(authentication);
        model.addAttribute("accounts", imapAccountRepository.findByTenantUserId(tenantUser.getId()));
        return "accounts";
    }

    @GetMapping("/accounts/link")
    public String linkForm(Model model) {
        model.addAttribute("linkImapAccountForm", new LinkImapAccountForm());
        return "link-account";
    }

    @PostMapping("/accounts/link")
    public String link(Authentication authentication,
                        @Valid @ModelAttribute LinkImapAccountForm form,
                        BindingResult bindingResult,
                        Model model) {
        if (bindingResult.hasErrors()) {
            return "link-account";
        }
        TenantUser tenantUser = currentTenantUser(authentication);
        try {
            linkingService.linkAccount(tenantUser, new ImapAccountLinkingService.LinkImapAccountRequest(
                    form.getDisplayName(), form.getHost(), form.getPort(), form.getTlsMode(),
                    form.getUsername(), form.getPassword()));
        } catch (ImapAccountLinkingService.LinkingFailedException e) {
            model.addAttribute("error", "Could not verify this IMAP login. Check the host/port/credentials and try again.");
            return "link-account";
        } catch (DataIntegrityViolationException e) {
            model.addAttribute("error", "You already have an account linked with that name. Choose a different name.");
            return "link-account";
        }
        return "redirect:/accounts";
    }

    private TenantUser currentTenantUser(Authentication authentication) {
        return tenantUserRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated principal has no matching TenantUser"));
    }
}
