package com.estatevista.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class CredentialVerificationServiceTest {

    private static final String RAW_PASSWORD = "Example-Password-123";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$12$stored-hash-value";
    private static final String DUMMY_ENCODED_PASSWORD = "{bcrypt}$2a$12$dummy-hash-value";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private CredentialVerificationService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_ENCODED_PASSWORD);
        service = new CredentialVerificationService(userRepository, passwordEncoder);
    }

    @Test
    void returnsVerifiedAccountWhenPasswordMatchesStoredHash() {
        User user = user("buyer@example.com", ENCODED_PASSWORD, AccountStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);

        Optional<CredentialVerificationService.VerifiedAccount> result =
                service.verify("  Buyer@Example.COM  ", RAW_PASSWORD);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(user.getId());
        assertThat(result.get().email()).isEqualTo("buyer@example.com");
        assertThat(result.get().roleName()).isEqualTo("BUYER");
        assertThat(result.get().accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result.get().toString()).doesNotContain(ENCODED_PASSWORD).doesNotContain(RAW_PASSWORD);
    }

    @Test
    void returnsEmptyWhenPasswordDoesNotMatch() {
        User user = user("buyer@example.com", ENCODED_PASSWORD, AccountStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", ENCODED_PASSWORD)).thenReturn(false);

        assertThat(service.verify("buyer@example.com", "wrong-password")).isEmpty();
    }

    @Test
    void missingAccountPerformsDummyComparisonAndReturnsEmpty() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(RAW_PASSWORD, DUMMY_ENCODED_PASSWORD)).thenReturn(false);

        assertThat(service.verify("missing@example.com", RAW_PASSWORD)).isEmpty();

        verify(passwordEncoder).matches(RAW_PASSWORD, DUMMY_ENCODED_PASSWORD);
        verify(passwordEncoder, never()).matches(RAW_PASSWORD, ENCODED_PASSWORD);
    }

    @Test
    void nullOrBlankInputsReturnEmptySafely() {
        assertThat(service.verify(null, RAW_PASSWORD)).isEmpty();
        assertThat(service.verify("   ", RAW_PASSWORD)).isEmpty();
        assertThat(service.verify("buyer@example.com", null)).isEmpty();

        // With a null/blank email there is no account lookup key.
        verify(userRepository, never()).findByEmailIgnoreCase(null);
        verify(userRepository, never()).findByEmailIgnoreCase("");
        // A valid email with a null password still resolves the account, but the
        // safe matcher rejects the null password without calling the encoder.
        verify(userRepository).findByEmailIgnoreCase("buyer@example.com");
        verify(passwordEncoder, never()).matches(org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    @Test
    void malformedOrUnsupportedHashIsTreatedAsFailure() {
        User user = user("buyer@example.com", "not-a-valid-hash", AccountStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, "not-a-valid-hash"))
                .thenThrow(new IllegalArgumentException("unknown id"));

        assertThat(service.verify("buyer@example.com", RAW_PASSWORD)).isEmpty();
    }

    @Test
    void nullStoredHashIsTreatedAsFailure() {
        User user = user("buyer@example.com", null, AccountStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));

        assertThat(service.verify("buyer@example.com", RAW_PASSWORD)).isEmpty();

        verify(passwordEncoder, never()).matches(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void differentUsersHashesRemainIndependent() {
        User first = user("first@example.com", "{bcrypt}$2a$12$hash-one", AccountStatus.ACTIVE);
        User second = user("second@example.com", "{bcrypt}$2a$12$hash-two", AccountStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("first@example.com")).thenReturn(Optional.of(first));
        when(userRepository.findByEmailIgnoreCase("second@example.com")).thenReturn(Optional.of(second));
        when(passwordEncoder.matches(RAW_PASSWORD, "{bcrypt}$2a$12$hash-one")).thenReturn(true);
        when(passwordEncoder.matches(RAW_PASSWORD, "{bcrypt}$2a$12$hash-two")).thenReturn(false);

        assertThat(service.verify("first@example.com", RAW_PASSWORD)).isPresent();
        assertThat(service.verify("second@example.com", RAW_PASSWORD)).isEmpty();
    }

    private static User user(String email, String passwordHash, AccountStatus status) {
        Role role = new Role();
        role.setName("BUYER");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setAccountStatus(status);
        user.setRole(role);
        user.setFirstName("Test");
        user.setLastName("User");
        return user;
    }
}
