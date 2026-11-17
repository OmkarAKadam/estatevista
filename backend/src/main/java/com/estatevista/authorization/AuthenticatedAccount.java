package com.estatevista.authorization;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.estatevista.user.entity.AccountStatus;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Trusted authorization view of an authenticated account.
 *
 * <p>Every field is resolved from the database using the server-side session
 * principal. No request body, query parameter, or header contributes to it.</p>
 */
public record AuthenticatedAccount(
        UUID id,
        PlatformRole role,
        AccountStatus accountStatus,
        List<GrantedAuthority> authorities) {

    public AuthenticatedAccount {
        authorities = List.copyOf(authorities);
    }

    /** Creates an account view with exactly one authority derived from its role. */
    public static AuthenticatedAccount of(UUID id, PlatformRole role, AccountStatus accountStatus) {
        return new AuthenticatedAccount(
                id,
                role,
                accountStatus,
                List.of(new SimpleGrantedAuthority(role.authority())));
    }

    /** Convenience for checks that need the authority collection as a generic type. */
    public Collection<GrantedAuthority> grantedAuthorities() {
        return authorities;
    }
}
