package com.estatevista.authorization;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authoritative role and status of the current account.
 *
 * <p>The session principal (the user UUID) is the only identity source. Client
 * supplied role information is never read. An inactive account, a missing
 * account, or an unknown role produces no authorities, which makes every
 * authorization decision fail closed.</p>
 */
@Service
@Transactional(readOnly = true)
public class AccountAuthorizationService {

    private final UserRepository userRepository;

    public AccountAuthorizationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Re-resolves an authenticated principal from trusted database state. */
    public Optional<AuthenticatedAccount> resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }

        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        return userRepository.findById(userId)
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE)
                .map(this::toAccount);
    }

    /** Resolves a STOMP/WebSocket principal, which is an {@link Authentication}. */
    public AuthenticatedAccount requireActivePrincipal(Principal principal) {
        if (!(principal instanceof Authentication authentication)) {
            throw unauthorized();
        }
        return requireActive(authentication);
    }

    public AuthenticatedAccount requireActive(Authentication authentication) {
        return resolve(authentication).orElseThrow(AccountAuthorizationService::unauthorized);
    }

    /** Enforces a single required role on a trusted account. */
    public void requireRole(AuthenticatedAccount account, PlatformRole required) {
        if (account == null || account.role() != required) {
            throw forbidden();
        }
    }

    private AuthenticatedAccount toAccount(User user) {
        if (user.getRole() == null) {
            return null;
        }
        return PlatformRole.fromDatabaseName(user.getRole().getName())
                .map(role -> AuthenticatedAccount.of(user.getId(), role, user.getAccountStatus()))
                .orElse(null);
    }

    private static ApiException unauthorized() {
        return new ApiException(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    private static ApiException forbidden() {
        return new ApiException(
                ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }
}
