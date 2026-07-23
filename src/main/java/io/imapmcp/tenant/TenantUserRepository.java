package io.imapmcp.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    Optional<TenantUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
