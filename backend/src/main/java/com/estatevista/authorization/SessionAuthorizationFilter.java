package com.estatevista.authorization;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-verifies server-side session authentications before authorization.
 *
 * <p>Spring Session persists the {@code SecurityContext} in the session. Without
 * this filter, an account deactivated after login or an account with an
 * unsupported role would keep its previously granted authorities. The filter
 * replaces those authorities with a fresh database-derived view on every
 * request and removes the stored context when the account is no longer
 * eligible. It only acts on {@link SessionAuthenticationToken} instances
 * created by the Stage 3.4 login flow.</p>
 */
public final class SessionAuthorizationFilter extends OncePerRequestFilter {

    private final AccountAuthorizationService accountAuthorizationService;
    private final SecurityContextRepository securityContextRepository;

    public SessionAuthorizationFilter(
            AccountAuthorizationService accountAuthorizationService,
            SecurityContextRepository securityContextRepository) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof SessionAuthenticationToken) {
            Optional<AuthenticatedAccount> account = accountAuthorizationService.resolve(authentication);
            if (account.isEmpty()) {
                SecurityContextHolder.clearContext();
                securityContextRepository.saveContext(
                        SecurityContextHolder.createEmptyContext(), request, response);
            } else {
                AuthenticatedAccount current = account.get();
                Authentication refreshed = new SessionAuthenticationToken(
                        current.id().toString(), current.authorities());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(refreshed);
                SecurityContextHolder.setContext(context);
            }
        }
        filterChain.doFilter(request, response);
    }
}
