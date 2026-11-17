package com.estatevista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.auth.dto.RegisterRequest;
import com.estatevista.auth.dto.RegisteredUserResponse;
import com.estatevista.auth.security.CredentialVerificationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String RAW_PASSWORD = "Example-Password-123";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$12$stored-hash-value";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @Mock
    private CredentialVerificationService credentialVerificationService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, roleRepository, passwordEncoder, credentialVerificationService);
    }

    @Test
    void rejectsCommonlyBreachedPasswordBeforeSaving() {
        assertThatThrownBy(() -> authService.register(request(
                "John", "Doe", "john@example.com", "Password123!", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registersBuyerWithNormalizedEmailAndHashedPassword() {
        Role buyer = roleNamed("BUYER");
        when(roleRepository.findByName("BUYER")).thenReturn(Optional.of(buyer));
        when(userRepository.findByEmailIgnoreCase("john.doe@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        RegisteredUserResponse response = authService.register(request(
                "  John  ", "  Doe  ", "  John.Doe@Example.COM  ", RAW_PASSWORD, " +919876543210 "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getRole()).isSameAs(buyer);
        assertThat(saved.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getEmail()).isEqualTo("john.doe@example.com");
        assertThat(saved.getFirstName()).isEqualTo("John");
        assertThat(saved.getLastName()).isEqualTo("Doe");
        assertThat(saved.getPhone()).isEqualTo("+919876543210");
        assertThat(saved.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);

        assertThat(response.id()).isEqualTo(saved.getId());
        assertThat(response.role()).isEqualTo("BUYER");
        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.email()).isEqualTo("john.doe@example.com");
    }

    @Test
    void treatsBlankPhoneAsAbsent() {
        when(roleRepository.findByName("BUYER")).thenReturn(Optional.of(roleNamed("BUYER")));
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        authService.register(request("John", "Doe", "john@example.com", RAW_PASSWORD, "   "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhone()).isNull();
    }

    @Test
    void rejectsDuplicateEmailBeforePersisting() {
        when(userRepository.findByEmailIgnoreCase("john@example.com"))
                .thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> authService.register(
                request("John", "Doe", "john@example.com", RAW_PASSWORD, null)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void translatesConcurrentUniqueViolationToConflict() {
        when(roleRepository.findByName("BUYER")).thenReturn(Optional.of(roleNamed("BUYER")));
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> authService.register(
                request("John", "Doe", "john@example.com", RAW_PASSWORD, null)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void rejectsPasswordEqualToAContextWord() {
        assertThatThrownBy(() -> authService.register(
                request("Johnathan", "Doe", "john@example.com", "Johnathan", null)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void failsClosedWhenBuyerRoleIsMissing() {
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(roleRepository.findByName("BUYER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(
                request("John", "Doe", "john@example.com", RAW_PASSWORD, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    private static Role roleNamed(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private static RegisterRequest request(
            String firstName, String lastName, String email, String password, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setEmail(email);
        request.setPassword(password);
        request.setPhone(phone);
        return request;
    }
}
