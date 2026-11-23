package com.estatevista.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Unit verification of trusted, database-derived account authorization. */
@ExtendWith(MockitoExtension.class)
class AccountAuthorizationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void resolvesActiveKnownRoleAccountWithAuthoritativeRole() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user(userId, "BUYER", AccountStatus.ACTIVE)));

        AuthenticatedAccount account = new AccountAuthorizationService(userRepository)
                .requireActive(authentication(userId));

        assertThat(account.id()).isEqualTo(userId);
        assertThat(account.role()).isEqualTo(PlatformRole.BUYER);
        assertThat(account.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.authorities()).extracting("authority").containsExactly("ROLE_BUYER");
    }

    @Test
    void inactiveAccountsDoNotResolve() {
        UUID userId = UUID.randomUUID();
        AccountAuthorizationService service = new AccountAuthorizationService(userRepository);

        when(userRepository.findById(userId))
                .thenReturn(java.util.Optional.of(user(userId, "AGENT", AccountStatus.INACTIVE)));
        assertThat(service.resolve(authentication(userId))).isEmpty();

        when(userRepository.findById(userId))
                .thenReturn(java.util.Optional.of(user(userId, "AGENT", AccountStatus.SUSPENDED)));
        assertThat(service.resolve(authentication(userId))).isEmpty();
    }

    @Test
    void unknownRoleDoesNotResolveEvenForAnActiveAccount() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId))
                .thenReturn(java.util.Optional.of(user(userId, "GUEST", AccountStatus.ACTIVE)));

        assertThat(new AccountAuthorizationService(userRepository).resolve(authentication(userId)))
                .as("an unrecognized role must grant no authority")
                .isEmpty();
    }

    @Test
    void missingOrAnonymousAuthenticationDoesNotResolve() {
        AccountAuthorizationService service = new AccountAuthorizationService(userRepository);

        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve(new UsernamePasswordAuthenticationToken(null, null))).isEmpty();
        assertThat(service.resolve(new UsernamePasswordAuthenticationToken("not-a-uuid", null))).isEmpty();
        assertThat(service.resolve(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))))
                .isEmpty();
    }

    @Test
    void requireActivePrincipalRejectsNonAuthenticationPrincipal() {
        Principal principal = () -> UUID.randomUUID().toString();

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> new AccountAuthorizationService(userRepository).requireActivePrincipal(principal))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void requireRoleEnforcesTheExactTrustedRole() {
        AccountAuthorizationService service = new AccountAuthorizationService(userRepository);
        UUID userId = UUID.randomUUID();
        AuthenticatedAccount buyer = AuthenticatedAccount.of(
                userId, PlatformRole.BUYER, AccountStatus.ACTIVE);

        service.requireRole(buyer, PlatformRole.BUYER);

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.requireRole(buyer, PlatformRole.ADMIN))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.requireRole(null, PlatformRole.BUYER))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static Authentication authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private static User user(UUID id, String roleName, AccountStatus status) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setAccountStatus(status);
        return user;
    }
}
