package com.estatevista.user.entity;

/**
 * Approved account status values (DATABASE_DESIGN.md §5.2).
 *
 * <p>Persisted as text so the value remains readable and matches the database
 * CHECK constraint.</p>
 */
public enum AccountStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED
}
