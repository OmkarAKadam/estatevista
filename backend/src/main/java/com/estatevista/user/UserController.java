package com.estatevista.user;

import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.user.dto.UpdateProfileRequest;
import com.estatevista.user.dto.UserProfileResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated self-service account endpoints (API §4.1).
 *
 * <p>Both operations act only on the authenticated principal. Password change
 * is deferred to the session-lifecycle sub-stage because it requires session
 * rotation and CSRF re-issuance.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> currentUser(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(userService.getProfile(authentication)));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateCurrentUser(
            Authentication authentication,
            @RequestBody UpdateProfileRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        "Profile updated",
                        userService.updateProfile(authentication, request)));
    }
}
