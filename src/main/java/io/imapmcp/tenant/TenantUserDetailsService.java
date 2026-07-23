package io.imapmcp.tenant;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class TenantUserDetailsService implements UserDetailsService {

    private final TenantUserRepository tenantUserRepository;

    public TenantUserDetailsService(TenantUserRepository tenantUserRepository) {
        this.tenantUserRepository = tenantUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        TenantUser tenantUser = tenantUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));

        return User.builder()
                .username(tenantUser.getEmail())
                .password(tenantUser.getPasswordHash())
                .disabled(tenantUser.getStatus() != TenantUser.Status.ACTIVE)
                .authorities("ROLE_USER")
                .build();
    }
}
