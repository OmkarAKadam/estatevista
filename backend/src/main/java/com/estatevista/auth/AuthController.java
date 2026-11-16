package com.estatevista.auth;

import com.estatevista.auth.dto.RegisterRequest;
import com.estatevista.auth.dto.RegisteredUserResponse;
import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints implemented in Stage 3.2.
 *
 * <p>Only buyer registration is exposed here. Login, logout, current-user
 * bootstrap, and password reset belong to later Stage 3 sub-stages.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisteredUserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        RegisteredUserResponse registered = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Account registered successfully", registered));
    }
}
