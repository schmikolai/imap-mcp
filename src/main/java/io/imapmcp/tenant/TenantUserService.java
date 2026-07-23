package io.imapmcp.tenant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantUserService {

    private final TenantUserRepository tenantUserRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantUserService(TenantUserRepository tenantUserRepository, PasswordEncoder passwordEncoder) {
        this.tenantUserRepository = tenantUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public TenantUser signUp(String email, String rawPassword) {
        if (tenantUserRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        TenantUser tenantUser = new TenantUser(email, passwordEncoder.encode(rawPassword));
        return tenantUserRepository.save(tenantUser);
    }

    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException(String email) {
            super("An account already exists for this email address");
        }
    }
}
