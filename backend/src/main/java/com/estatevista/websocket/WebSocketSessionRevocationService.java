package com.estatevista.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.config.WebSocketSecurityProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Internal, reusable revocation mechanism for Stage 7.2 and future account
 * management. It performs authoritative session deletion, closes the actual
 * WebSocket transport and removes registry/subscription state.
 *
 * <p>No HTTP endpoint, administrator role or account lifecycle policy is
 * introduced. Callers already authorized by their own workflow invoke these
 * methods directly.</p>
 */
@Service
public final class WebSocketSessionRevocationService {

    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_SESSION_REVOKED = "SESSION_REVOKED";
    public static final String REASON_ACCOUNT_REVOKED = "ACCOUNT_REVOKED";
    public static final String REASON_SESSION_ROTATED = "SESSION_ROTATED";
    public static final String REASON_AUTHORIZATION_LOSS = "AUTHORIZATION_LOSS";
    public static final String REASON_SESSION_EXPIRED = "SESSION_EXPIRED";
    public static final String REASON_VALIDATION_FAILURE = "VALIDATION_FAILURE";
    public static final String REASON_CONNECTION_LIMIT = "CONNECTION_LIMIT";

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRevocationService.class);
    private static final CloseStatus CLOSE_STATUS = new CloseStatus(
            4003, "WebSocket session is no longer authorized");

    private final WebSocketConnectionRegistry registry;
    private final AuthoritativeSessionValidator validator;
    private final AuditEventService audit;
    private final WebSocketSecurityProperties properties;

    public WebSocketSessionRevocationService(
            WebSocketConnectionRegistry registry,
            AuthoritativeSessionValidator validator,
            AuditEventService audit,
            WebSocketSecurityProperties properties) {
        this.registry = registry;
        this.validator = validator;
        this.audit = audit;
        this.properties = properties;
    }

    /** Closes sockets for one Spring Session without invalidating the HTTP session itself. */
    public int closeSocketsForSession(String springSessionId, String reason) {
        int closed = 0;
        for (WebSocketConnectionRegistry.ConnectionRecord record
                : registry.forSpringSession(springSessionId)) {
            if (closeRecord(record, reason)) {
                closed++;
            }
        }
        return closed;
    }

    /** Session-specific revocation: sockets plus the authoritative session record. */
    public int revokeSession(String springSessionId, String reason) {
        int closed = closeSocketsForSession(springSessionId, reason);
        validator.deleteSession(springSessionId);
        return closed;
    }

    /** Account-wide revocation: every indexed session and every socket for the account. */
    public int revokeAccount(UUID userId, String reason) {
        if (userId == null) {
            return 0;
        }
        Map<String, ? extends Session> sessions = validator.sessionsForPrincipal(userId.toString());
        for (String sessionId : sessions.keySet()) {
            validator.deleteSession(sessionId);
        }
        int closed = 0;
        for (WebSocketConnectionRegistry.ConnectionRecord record : registry.forUser(userId)) {
            if (closeRecord(record, reason)) {
                closed++;
            }
        }
        return closed;
    }

    /** Closes stale sockets after Spring Session identifier rotation. */
    public int revokeStaleSession(String oldSessionId, String newSessionId) {
        if (oldSessionId == null || oldSessionId.equals(newSessionId)) {
            return 0;
        }
        int closed = closeSocketsForSession(oldSessionId, REASON_SESSION_ROTATED);
        validator.invalidateCache(oldSessionId);
        return closed;
    }

    /** Closes one connection, its subscriptions and its transport. */
    public boolean revokeConnection(
            WebSocketConnectionRegistry.ConnectionRecord record, String reason) {
        return closeRecord(record, reason);
    }

    public boolean revokeConnection(String connectionId, String reason) {
        return closeRecord(registry.byHandshake(connectionId), reason);
    }

    /** Clears any bounded positive-result cache entry for a destroyed session. */
    public void invalidateCache(String springSessionId) {
        validator.invalidateCache(springSessionId);
    }

    private boolean closeRecord(
            WebSocketConnectionRegistry.ConnectionRecord record, String reason) {
        if (record == null || record.state() == WebSocketConnectionRegistry.State.CLOSED) {
            return false;
        }
        WebSocketSession transport;
        record.writeLock().lock();
        try {
            if (record.state() == WebSocketConnectionRegistry.State.CLOSED) {
                return false;
            }
            // Mark closed under the same lock used by outbound dispatch. An
            // in-flight preSend either completes first or sees CLOSED and drops.
            record.markClosedUnderLock();
            transport = record.transport();
        } finally {
            record.writeLock().unlock();
        }

        if (transport != null && transport.isOpen()) {
            try {
                transport.close(CLOSE_STATUS);
            } catch (IOException | RuntimeException exception) {
                log.warn("WebSocket transport close failed: {}", exception.getClass().getSimpleName());
            }
        }
        registry.remove(record.connectionId());
        UUID correlationId = UUID.randomUUID();
        audit.recordWebSocketSecurityEvent(
                record.userId(), AuditEventService.ACTION_WS_SESSION_REVOKED,
                safeReason(reason), "session", correlationId);
        return true;
    }

    private static String safeReason(String reason) {
        return reason != null && reason.matches("[A-Z_]{1,40}") ? reason : REASON_SESSION_REVOKED;
    }

    /** Used by the bounded revalidation task; kept public for deterministic tests. */
    public boolean revalidateIdleConnections(
            WebSocketConnectionRegistry.ConnectionRecord record, String reason) {
        return closeRecord(record, reason);
    }

    public WebSocketSecurityProperties properties() {
        return properties;
    }
}
