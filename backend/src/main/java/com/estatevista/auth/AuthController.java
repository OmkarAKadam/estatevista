package com.estatevista.auth;

import com.estatevista.auth.dto.AuthenticatedUserResponse;
import com.estatevista.auth.dto.CsrfTokenResponse;
import com.estatevista.auth.dto.LoginRequest;
import com.estatevista.auth.dto.RegisterRequest;
import com.estatevista.auth.dto.RegisteredUserResponse;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.SessionAuthenticationToken;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.user.entity.AccountStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication and session-lifecycle endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

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

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<CsrfTokenResponse>> csrf(CsrfToken csrfToken) {
        String token = csrfToken == null ? null : csrfToken.getToken();
        String headerName = csrfToken == null ? CSRF_HEADER : csrfToken.getHeaderName();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(CSRF_HEADER, token == null ? "" : token)
                .body(ApiResponse.success(new CsrfTokenResponse(headerName, token)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            CsrfToken csrfToken) {
        UnknownFieldGuard.reject(request.unknownFields());
        AuthenticatedUserResponse user = authService.authenticate(request);

        // Rotate any pre-existing anonymous session after successful credential
        // verification. Spring Security's sessionFixation() strategy is not
        // triggered by a controller-managed authentication, so the rotation is
        // applied explicitly here. changeSessionId() preserves session
        // attributes (including the CSRF token) while replacing the identifier.
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        AuthenticatedAccount account = trustedAccount(user);
        SessionAuthenticationToken authentication =
                new SessionAuthenticationToken(account.id().toString(), account.authorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        String token = csrfToken == null ? null : csrfToken.getToken();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        if (token != null) {
            response.header(CSRF_HEADER, token);
        }
        return response.body(ApiResponse.success("Login successful", user));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> currentUser(
            Authentication authentication,
            CsrfToken csrfToken) {
        AuthenticatedUserResponse user = authService.currentUser(authentication);
        String token = csrfToken == null ? null : csrfToken.getToken();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        if (token != null) {
            response.header(CSRF_HEADER, token);
        }
        return response.body(ApiResponse.success(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Object>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        var cookie = new jakarta.servlet.http.Cookie("EV_SESSION", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Logout successful", null));
    }

    /**
     * Grants authorities only from the authenticated account loaded by
     * {@link AuthService#authenticate}. Role and status are trusted database
     * values, never request input. Unknown roles and non-active accounts fail
     * closed before a session is established.
     */
    private static AuthenticatedAccount trustedAccount(AuthenticatedUserResponse user) {
        if (user == null || user.id() == null) {
            throw unauthorized();
        }
        PlatformRole role = PlatformRole.fromDatabaseName(user.role())
                .orElseThrow(AuthController::unauthorized);
        AccountStatus status;
        try {
            status = AccountStatus.valueOf(user.accountStatus());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unauthorized();
        }
        if (status != AccountStatus.ACTIVE) {
            throw unauthorized();
        }
        return AuthenticatedAccount.of(user.id(), role, status);
    }

    private static ApiException unauthorized() {
        return new ApiException(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }
}
