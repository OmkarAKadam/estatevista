package com.estatevista.websocket;

import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.config.AllowedOrigins;
import com.estatevista.config.AppProperties;
import com.estatevista.security.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Trusted-user WebSocket handshake boundary. */
@Component
public final class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "x-user-id", "x-role", "x-roles", "x-session-id", "x-csrf-token");

    private final SecurityContextRepository securityContextRepository;
    private final AccountAuthorizationService accounts;
    private final AuthoritativeSessionValidator validator;
    private final WebSocketConnectionRegistry registry;
    private final WebSocketSessionRevocationService revocation;
    private final RateLimitService rateLimitService;
    private final AuditEventService audit;
    private final List<String> allowedOrigins;

    public WebSocketHandshakeInterceptor(
            AppProperties properties,
            SecurityContextRepository securityContextRepository,
            AccountAuthorizationService accounts,
            AuthoritativeSessionValidator validator,
            WebSocketConnectionRegistry registry,
            WebSocketSessionRevocationService revocation,
            RateLimitService rateLimitService,
            AuditEventService audit) {
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
        this.validator = validator;
        this.registry = registry;
        this.revocation = revocation;
        this.rateLimitService = rateLimitService;
        this.audit = audit;
        this.allowedOrigins = AllowedOrigins.requireExplicit(
                properties.websocket().allowedOrigins(), "app.websocket.allowed-origins");
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UUID correlationId = UUID.randomUUID();
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            deny(response, "VALIDATION_FAILURE", null, correlationId);
            return false;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        String origin = singleOrigin(httpRequest);
        if (origin == null || !allowedOrigins.contains(origin)) {
            deny(response, "INVALID_ORIGIN", null, correlationId);
            return false;
        }
        if (hasClientIdentityOverride(httpRequest)) {
            deny(response, "IDENTITY_OVERRIDE", null, correlationId);
            return false;
        }
        if (httpRequest.getQueryString() != null && !httpRequest.getQueryString().isBlank()) {
            deny(response, "QUERY_CREDENTIALS", null, correlationId);
            return false;
        }

        String clientIp = httpRequest.getRemoteAddr();
        try {
            rateLimitService.acquire(
                    RateLimitService.SCOPE_WS_CONNECTION_ATTEMPT, "ip:" + clientIp);
        } catch (RuntimeException exception) {
            deny(response, "RATE_LIMITED", null, correlationId);
            return false;
        }

        HttpSession httpSession = httpRequest.getSession(false);
        if (httpSession == null) {
            deny(response, "NOT_AUTHENTICATED", null, correlationId);
            return false;
        }
        String springSessionId = httpSession.getId();
        SecurityContext context = securityContextRepository.loadDeferredContext(httpRequest).get();
        Authentication authentication = context == null ? null : context.getAuthentication();
        AuthenticatedAccount account = accounts.resolve(authentication).orElse(null);
        if (account == null
                || (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT)) {
            deny(response, "ROLE_NOT_ELIGIBLE", account, correlationId);
            return false;
        }

        AuthoritativeSessionValidator.ValidationResult sessionResult =
                validator.validate(springSessionId, account.id().toString(), null, false);
        if (!sessionResult.valid()) {
            deny(response, auditReason(sessionResult.reason().name()), account, correlationId);
            return false;
        }

        Object binding = httpSession.getAttribute(WebSocketSessionAttributes.SESSION_BINDING);
        if (binding instanceof String bound && !bound.equals(springSessionId)) {
            revocation.revokeStaleSession(bound, springSessionId);
        }
        httpSession.setAttribute(WebSocketSessionAttributes.SESSION_BINDING, springSessionId);

        WebSocketConnectionRegistry.ConnectionRecord record = registry.registerPending(
                springSessionId, account.id(), authentication, account.role(), clientIp);
        if (record == null) {
            deny(response, "CONNECTION_LIMIT", account, correlationId);
            return false;
        }

        attributes.put(WebSocketSessionAttributes.HANDSHAKE_ID, record.connectionId());
        attributes.put(WebSocketSessionAttributes.SPRING_SESSION_ID, springSessionId);
        attributes.put(WebSocketSessionAttributes.USER_ID, account.id().toString());
        attributes.put(WebSocketSessionAttributes.ROLE, account.role().name());
        attributes.put(WebSocketSessionAttributes.PRINCIPAL, authentication);
        attributes.put(WebSocketSessionAttributes.CLIENT_IP, clientIp);
        attributes.put(WebSocketSessionAttributes.ORIGIN, origin);
        attributes.put(WebSocketSessionAttributes.CONNECTED_AT, Instant.now());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // The transport is bound by WebSocketTransportLifecycle, which receives
        // the actual WebSocketSession. No session identifier or token is logged.
    }

    private static String singleOrigin(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Origin");
        if (values == null) {
            return null;
        }
        List<String> list = Collections.list(values);
        return list.size() == 1 ? list.get(0) : null;
    }

    private static boolean hasClientIdentityOverride(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return false;
        }
        return Collections.list(names).stream()
                .map(String::toLowerCase)
                .anyMatch(FORBIDDEN_HEADERS::contains);
    }

    private static String auditReason(String reason) {
        return reason != null && reason.matches("[A-Z_]{1,40}") ? reason : "VALIDATION_FAILURE";
    }

    private void deny(
            ServerHttpResponse response, String reason,
            AuthenticatedAccount account, UUID correlationId) {
        audit.recordWebSocketSecurityEvent(
                account == null ? null : account.id(),
                AuditEventService.ACTION_WS_AUTH_FAILURE,
                reason, "handshake", correlationId);
        response.setStatusCode(HttpStatus.FORBIDDEN);
    }
}
