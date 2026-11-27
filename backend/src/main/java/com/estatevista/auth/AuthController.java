package com.estatevista.auth;

import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.auth.dto.AuthenticatedUserResponse;
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
import com.estatevista.security.RateLimitService;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.websocket.WebSocketSessionRevocationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
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
    private final RateLimitService rateLimitService;
    private final AuditEventService auditEventService;
    private final CsrfTokenRepository csrfTokenRepository;
    private final WebSocketSessionRevocationService webSocketRevocation;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /** Constructor retained for isolated authentication unit tests. */
    public AuthController(
            AuthService authService,
            RateLimitService rateLimitService,
            AuditEventService auditEventService,
            CsrfTokenRepository csrfTokenRepository) {
        this(authService, rateLimitService, auditEventService, csrfTokenRepository,
                (WebSocketSessionRevocationService) null);
    }

    /** Constructor retained for explicit revocation wiring in isolation. */
    public AuthController(
            AuthService authService,
            RateLimitService rateLimitService,
            AuditEventService auditEventService,
            CsrfTokenRepository csrfTokenRepository,
            WebSocketSessionRevocationService webSocketRevocation) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.auditEventService = auditEventService;
        this.csrfTokenRepository = csrfTokenRepository;
        this.webSocketRevocation = webSocketRevocation;
    }

    /**
     * Spring wiring. The provider keeps MVC slice tests independent of the
     * WebSocket infrastructure while the full application always resolves the
     * concrete revocation service.
     */
    @Autowired
    public AuthController(
            AuthService authService,
            RateLimitService rateLimitService,
            AuditEventService auditEventService,
            CsrfTokenRepository csrfTokenRepository,
            ObjectProvider<WebSocketSessionRevocationService> webSocketRevocationProvider) {
        this(authService, rateLimitService, auditEventService, csrfTokenRepository,
                webSocketRevocationProvider.getIfAvailable());
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisteredUserResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        UnknownFieldGuard.reject(request.unknownFields());
        rateLimitService.acquire(RateLimitService.SCOPE_REGISTRATION, httpRequest.getRemoteAddr());
        RegisteredUserResponse registered = authService.register(request);
        auditEventService.recordRegistration(registered.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Account registered successfully", registered));
    }

    /**
     * Compatibility CSRF bootstrap. The session-bound token is transported
     * exclusively through {@code X-CSRF-TOKEN}; the response envelope carries
     * no token or alternative token field.
     */
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<Void>> csrf(CsrfToken csrfToken) {
        String token = csrfToken == null ? null : csrfToken.getToken();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        if (token != null) {
            response.header(CSRF_HEADER, token);
        }
        return response.body(ApiResponse.<Void>success("CSRF token issued", null));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            CsrfToken csrfToken) {
        UnknownFieldGuard.reject(request.unknownFields());
        String rateLimitIdentity = request.getEmail() + "|" + httpRequest.getRemoteAddr();
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, rateLimitIdentity);
        AuthenticatedUserResponse user;
        AuthenticatedAccount account;
        try {
            user = authService.authenticate(request);
            account = trustedAccount(user);
        } catch (ApiException exception) {
            auditEventService.recordLoginFailure();
            throw exception;
        }

        // Rotate any pre-existing anonymous session after a fully trusted
        // authentication. Spring Security's sessionFixation() strategy is not
        // triggered by a controller-managed authentication, so the rotation is
        // applied explicitly here. changeSessionId() preserves session attributes
        // while replacing the identifier; the CSRF token is regenerated below so
        // the pre-authentication token cannot be reused after login (SD-F2).
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        SessionAuthenticationToken authentication =
                new SessionAuthenticationToken(account.id().toString(), account.authorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        CsrfToken regenerated = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(regenerated, httpRequest, httpResponse);
        httpRequest.setAttribute(CsrfToken.class.getName(), regenerated);
        httpRequest.setAttribute(regenerated.getParameterName(), regenerated);

        rateLimitService.reset(RateLimitService.SCOPE_LOGIN, rateLimitIdentity);
        auditEventService.recordLoginSuccess(user.id());

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        response.header(CSRF_HEADER, regenerated.getToken());
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
        UUID actorId = authenticatedActorId();
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            String sessionId = session.getId();
            // Close tracked sockets before the HTTP session disappears. Later
            // frames are rejected by authoritative session validation even if
            // a client races the close frame.
            if (webSocketRevocation != null) {
                webSocketRevocation.closeSocketsForSession(
                        sessionId, WebSocketSessionRevocationService.REASON_LOGOUT);
            }
            session.invalidate();
            if (webSocketRevocation != null) {
                webSocketRevocation.invalidateCache(sessionId);
            }
        }
        var cookie = new jakarta.servlet.http.Cookie("EV_SESSION", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        auditEventService.recordLogout(actorId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Logout successful", null));
    }

    private static UUID authenticatedActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
