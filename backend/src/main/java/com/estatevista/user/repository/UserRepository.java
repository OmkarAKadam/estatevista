package com.estatevista.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.user.entity.User;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Serializes account-status-sensitive checks for transactional workflows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u join fetch u.role where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
