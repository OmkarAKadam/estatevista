package com.estatevista.auth.dto;

import java.util.UUID;

/**
 * Safe registration response ({@code 201 Created}, API §3.3.1).
 *
 * <p>Contains only the approved public fields. The password, its hash, session
 * identifiers, and internal timestamps are never part of this contract.</p>
 */
public record RegisteredUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String role,
        String accountStatus) {
}
