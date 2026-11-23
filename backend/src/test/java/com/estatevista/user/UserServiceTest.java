package com.estatevista.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.dto.UpdateProfileRequest;
import com.estatevista.user.dto.UserProfileResponse;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String EMAIL = "buyer@example.com";
    private static final String HASH = "{bcrypt}$2a$12$stored-hash-value";

    @Mock
    private UserRepository userRepository;

    private UserService userService;
    private User user;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
        user = userWith(UUID.randomUUID());
        authentication = authenticatedAs(user.getId());
    }

    @Test
    void returnsTheAuthenticatedUsersOwnProfile() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getProfile(authentication);

        assertThat(profile.id()).isEqualTo(user.getId());
        assertThat(profile.firstName()).isEqualTo("John");
        assertThat(profile.lastName()).isEqualTo("Doe");
        assertThat(profile.email()).isEqualTo(EMAIL);
        assertThat(profile.phone()).isEqualTo("+919876543210");
        assertThat(profile.role()).isEqualTo("BUYER");
        assertThat(profile.accountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsMissingAuthentication() {
        assertUnauthorized(() -> userService.getProfile(null));
    }

    @Test
    void rejectsAnonymousAuthentication() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertUnauthorized(() -> userService.getProfile(anonymous));
    }

    @Test
    void rejectsMalformedPrincipalName() {
        Authentication malformed = new UsernamePasswordAuthenticationToken(
                "not-a-uuid", "n/a", List.of(new SimpleGrantedAuthority("ROLE_BUYER")));

        assertUnauthorized(() -> userService.getProfile(malformed));
    }

    @Test
    void rejectsPrincipalWithoutAnAccount() {
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertUnauthorized(() -> userService.getProfile(authenticatedAs(unknownId)));
    }

    @Test
    void updatesOnlyPermittedProfileFields() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setFirstName("  Janet  ");
        request.setPhone(" +441234567890 ");

        UserProfileResponse profile = userService.updateProfile(authentication, request);

        assertThat(profile.firstName()).isEqualTo("Janet");
        assertThat(profile.lastName()).isEqualTo("Doe");
        assertThat(profile.phone()).isEqualTo("+441234567890");
        assertThat(user.getRole().getName()).isEqualTo("BUYER");
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        verify(userRepository).save(user);
    }

    @Test
    void allowsClearingTheOptionalPhoneNumber() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setPhone(null);

        UserProfileResponse profile = userService.updateProfile(authentication, request);

        assertThat(profile.phone()).isNull();
    }

    @Test
    void rejectsAnEmptyPatch() {
        assertValidation(() -> userService.updateProfile(authentication, new UpdateProfileRequest()));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsBlankNames() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setFirstName("   ");

        assertValidation(() -> userService.updateProfile(authentication, request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsNamesLongerThanTheColumnLimit() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setLastName("a".repeat(101));

        assertValidation(() -> userService.updateProfile(authentication, request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsPhoneNumbersLongerThanTheColumnLimit() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setPhone("1".repeat(21));

        assertValidation(() -> userService.updateProfile(authentication, request));
        verify(userRepository, never()).save(any(User.class));
    }

    private static void assertUnauthorized(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private static void assertValidation(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private static Authentication authenticatedAs(UUID userId) {
        return new UsernamePasswordAuthenticationToken(
                userId.toString(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    private static User userWith(UUID id) {
        Role role = new Role();
        role.setName("BUYER");

        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setEmail(EMAIL);
        user.setPhone("+919876543210");
        user.setPasswordHash(HASH);
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call();
    }
}
