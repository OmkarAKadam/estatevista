package com.estatevista.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.user.entity.Role;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for account roles.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    /**
     * Role lookup by its unique name, required for assigning the single primary
     * role to an account (DATABASE_DESIGN.md §5.1, §5.2).
     */
    Optional<Role> findByName(String name);
}
