package com.estatevista.user;

import java.util.List;
import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;
import com.estatevista.user.dto.UpdateProfileRequest;
import com.estatevista.user.dto.UserProfileResponse;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account operations (API §4.1).
 *
 * <p>The account is always resolved from the authenticated principal. No
 * request parameter or body field can select a different account, so ownership
 * is implicit and cannot be overridden by a client.</p>
 */
@Service
public class UserService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PHONE_LENGTH = 20;

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Authentication authentication) {
        return toProfile(requireCurrentUser(authentication));
    }

    @Transactional
    public UserProfileResponse updateProfile(
            Authentication authentication, UpdateProfileRequest request) {
        requireAtLeastOneUpdate(request);

        String firstName = request.hasField("firstName")
                ? requireName("firstName", request.getFirstName())
                : null;
        String lastName = request.hasField("lastName")
                ? requireName("lastName", request.getLastName())
                : null;
        String phone = request.hasField("phone")
                ? normalizePhone(request.getPhone())
                : null;

        User user = requireCurrentUser(authentication);
        if (request.hasField("firstName")) {
            user.setFirstName(firstName);
        }
        if (request.hasField("lastName")) {
            user.setLastName(lastName);
        }
        if (request.hasField("phone")) {
            user.setPhone(phone);
        }
        userRepository.save(user);
        return toProfile(user);
    }

    private User requireCurrentUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw unauthorized();
        }

        // The approved principal name is the user UUID (Stage 3.1 assessment
        // D-1), which also fits SPRING_SESSION.PRINCIPAL_NAME.
        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }

        return userRepository.findById(userId).orElseThrow(UserService::unauthorized);
    }

    private static void requireAtLeastOneUpdate(UpdateProfileRequest request) {
        if (!request.hasAnyUpdate()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    List.of(new FieldErrorDetail(
                            "request",
                            "must contain at least one of firstName, lastName, or phone")));
        }
    }

    private static String requireName(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw fieldError(field, "must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw fieldError(field, "must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_PHONE_LENGTH) {
            throw fieldError("phone", "must be at most " + MAX_PHONE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorDetail(field, message)));
    }

    private static ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required");
    }

    private static UserProfileResponse toProfile(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().getName(),
                user.getAccountStatus().name());
    }
}
