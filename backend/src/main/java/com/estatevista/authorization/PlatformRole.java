package com.estatevista.authorization;

import java.util.Optional;

/**
 * Server-side role-to-authority mapping (Stage 3.5).
 *
 * <p>The names are the exact values stored in the {@code roles.name} column by
 * Flyway migration {@code V2}. Mapping is deliberately exhaustive: an unrecognized
 * database value yields no authority so authorization fails closed.</p>
 */
public enum PlatformRole {

    BUYER("ROLE_BUYER"),
    AGENT("ROLE_AGENT"),
    ADMIN("ROLE_ADMIN");

    private final String authority;

    PlatformRole(String authority) {
        this.authority = authority;
    }

    /** Spring Security authority granted for this role. */
    public String authority() {
        return authority;
    }

    /**
     * Maps a trusted database role name to a platform role.
     *
     * <p>Unknown or unsupported names are not normalized or guessed. Returning
     * {@link Optional#empty()} forces callers to deny access rather than granting
     * a privilege based on client input.</p>
     */
    public static Optional<PlatformRole> fromDatabaseName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (PlatformRole role : values()) {
            if (role.name().equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
