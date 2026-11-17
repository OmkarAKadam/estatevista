package com.estatevista.auth.dto;

import java.util.UUID;

/**
 * Safe authenticated account representation returned by login and session
 * inspection endpoints.
 *
 * <p>Contains only approved identity fields. Passwords, password hashes,
 * session identifiers, CSRF secrets, and audit/internal timestamps are never
 * included.</p>
 */
public record AuthenticatedUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String role,
        String accountStatus) {
}
