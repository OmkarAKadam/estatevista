package com.estatevista.websocket;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.config.SecureCsrfTokenRepository;
import com.estatevista.config.WebSocketSecurityProperties;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

/**
 * Authoritative Spring Session JDBC validation for WebSocket connections.
 *
 * <p>Handshake, CONNECT and protected frames call {@link #validate} with
 * {@code allowCached=false}; heartbeats and the bounded revalidation task may
 * use a short-lived positive-result cache. Destruction events are never used
 * as the authority. A repository failure always fails closed.</p>
 */
@Service
public final class AuthoritativeSessionValidator {

    public enum Reason {
        OK,
        MISSING_SESSION,
        EXPIRED_SESSION,
        PRINCIPAL_MISMATCH,
        ACCOUNT_UNAVAILABLE,
        ROLE_NOT_ELIGIBLE,
        BINDING_MISMATCH,
        VALIDATION_FAILURE
    }

    public record ValidationResult(boolean valid, Reason reason, PlatformRole role) {
        static ValidationResult ok(PlatformRole role) {
            return new ValidationResult(true, Reason.OK, role);
        }

        static ValidationResult invalid(Reason reason) {
            return new ValidationResult(false, reason, null);
        }
    }

    private record CacheEntry(Instant expiresAt, ValidationResult result) {
    }

    private final JdbcIndexedSessionRepository sessionRepository;
    private final AccountAuthorizationService accounts;
    private final WebSocketSecurityProperties properties;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AuthoritativeSessionValidator(
            JdbcIndexedSessionRepository sessionRepository,
            AccountAuthorizationService accounts,
            WebSocketSecurityProperties properties) {
        this.sessionRepository = sessionRepository;
        this.accounts = accounts;
        this.properties = properties;
    }

    /** Validates existence, expiry, identity, account status and chat role. */
    public ValidationResult validate(
            String sessionId, String expectedUserId, String expectedBinding, boolean allowCached) {
        if (sessionId == null || sessionId.isBlank()
                || expectedUserId == null || expectedUserId.isBlank()) {
            return ValidationResult.invalid(Reason.MISSING_SESSION);
        }
        if (allowCached && properties.getValidationCacheTtlMs() > 0) {
            CacheEntry cached = cache.get(sessionId);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.result();
            }
            if (cached != null) {
                cache.remove(sessionId);
            }
        }

        Session session;
        try {
            session = sessionRepository.findById(sessionId);
        } catch (RuntimeException exception) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.VALIDATION_FAILURE);
        }
        if (session == null) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.MISSING_SESSION);
        }
        if (session.isExpired()) {
            deleteSession(sessionId);
            return ValidationResult.invalid(Reason.EXPIRED_SESSION);
        }

        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Authentication authentication = context instanceof org.springframework.security.core.context.SecurityContext sc
                ? sc.getAuthentication() : null;
        if (authentication == null || !authentication.isAuthenticated()
                || !expectedUserId.equals(authentication.getName())) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.PRINCIPAL_MISMATCH);
        }

        Optional<AuthenticatedAccount> account = accounts.resolve(authentication);
        if (account.isEmpty()) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.ACCOUNT_UNAVAILABLE);
        }
        PlatformRole role = account.get().role();
        if (role != PlatformRole.BUYER && role != PlatformRole.AGENT) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.ROLE_NOT_ELIGIBLE);
        }
        if (expectedBinding != null
                && !expectedBinding.equals(session.getAttribute(WebSocketSessionAttributes.SESSION_BINDING))) {
            invalidateCache(sessionId);
            return ValidationResult.invalid(Reason.BINDING_MISMATCH);
        }

        ValidationResult result = ValidationResult.ok(role);
        if (allowCached && properties.getValidationCacheTtlMs() > 0) {
            boundCache();
            cache.put(sessionId, new CacheEntry(
                    Instant.now().plusMillis(properties.getValidationCacheTtlMs()), result));
        }
        return result;
    }

    /** Returns the authoritative session-bound CSRF value, never a generated fallback. */
    public String csrfToken(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Session session = sessionRepository.findById(sessionId);
            if (session == null || session.isExpired()) {
                return null;
            }
            Object token = session.getAttribute(SecureCsrfTokenRepository.CSRF_SESSION_ATTRIBUTE_NAME);
            return token instanceof CsrfToken csrfToken ? csrfToken.getToken() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public Map<String, ? extends Session> sessionsForPrincipal(String userId) {
        return sessionRepository.findByPrincipalName(userId);
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessionRepository.deleteById(sessionId);
        } catch (RuntimeException exception) {
            // Fail closed is handled by the caller; the cache is always cleared.
        } finally {
            invalidateCache(sessionId);
        }
    }

    public void invalidateCache(String sessionId) {
        if (sessionId != null) {
            cache.remove(sessionId);
        }
    }

    private void boundCache() {
        if (cache.size() < properties.getValidationCacheMaxEntries()) {
            return;
        }
        Instant now = Instant.now();
        cache.forEach((key, entry) -> {
            if (!entry.expiresAt().isAfter(now)) {
                cache.remove(key);
            }
        });
        while (cache.size() >= properties.getValidationCacheMaxEntries()) {
            String oldest = cache.keySet().stream().findFirst().orElse(null);
            if (oldest == null) {
                return;
            }
            cache.remove(oldest);
        }
    }
}
