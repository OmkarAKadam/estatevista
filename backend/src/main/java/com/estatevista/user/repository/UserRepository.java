package com.estatevista.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.user.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for registered accounts.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Case-insensitive account lookup by email. The database enforces
     * case-insensitive uniqueness (§5.2, §23.1 E); this lookup matches that
     * contract for authentication and account checks.
     */
    Optional<User> findByEmailIgnoreCase(String email);
}
