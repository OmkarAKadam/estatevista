package com.estatevista.authorization;

import java.util.Collection;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication created by login and stored in the server-side session.
 *
 * <p>The marker type lets the request filter distinguish real session
 * authentications from test/dummy {@code Authentication} objects. Only marked
 * authentications are re-verified against the database on each request, so a
 * role change or account deactivation cannot leave stale privileges in an
 * existing session.</p>
 */
public final class SessionAuthenticationToken extends UsernamePasswordAuthenticationToken {

    private static final long serialVersionUID = 1L;

    public SessionAuthenticationToken(
            String principal, Collection<? extends GrantedAuthority> authorities) {
        super(principal, null, authorities);
    }
}
