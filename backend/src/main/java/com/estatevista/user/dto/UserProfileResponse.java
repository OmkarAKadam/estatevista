package com.estatevista.user.dto;

import java.util.UUID;

/**
 * Safe representation of the authenticated user's own account (API §3.3.3,
 * §4.1).
 *
 * <p>No password hash, role identifier, verification timestamp, or audit field
 * is exposed.</p>
 */
public record UserProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        String accountStatus) {
}
