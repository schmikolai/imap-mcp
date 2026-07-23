package io.imapmcp.web;

import io.imapmcp.tenant.TenantUserService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.validation.BindingResult;

@Controller
public class SignupController {

    private final TenantUserService tenantUserService;

    public SignupController(TenantUserService tenantUserService) {
        this.tenantUserService = tenantUserService;
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupRequest", new SignupRequest());
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute SignupRequest signupRequest, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            return "signup";
        }
        try {
            tenantUserService.signUp(signupRequest.getEmail(), signupRequest.getPassword());
        } catch (TenantUserService.EmailAlreadyRegisteredException e) {
            // Generic message regardless of cause to avoid confirming which
            // emails are already registered (account-enumeration hardening).
            model.addAttribute("error", "Unable to create account with these details.");
            return "signup";
        }
        return "redirect:/login?signupSuccess";
    }
}
