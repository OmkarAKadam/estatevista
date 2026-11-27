package com.estatevista.websocket;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Final outbound boundary. Every protected STOMP frame is authorized against
 * live connection state immediately before dispatch; a failed recheck drops
 * the frame and records a redacted security event without message content.
 */
@Component
public final class WebSocketOutboundAuthorizationInterceptor implements ChannelInterceptor {

    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages", "/user/queue/notifications", "/user/queue/errors");
    private static final Set<SimpMessageType> FRAMEWORK_MESSAGES = Set.of(
            SimpMessageType.CONNECT_ACK, SimpMessageType.HEARTBEAT, SimpMessageType.DISCONNECT_ACK);
    private static final int MAX_AUDIT_KEYS = 512;
    private static final long AUDIT_COOLDOWN_MS = 30_000L;

    private final WebSocketConnectionRegistry registry;
    private final AuthoritativeSessionValidator validator;
    private final WebSocketSessionRevocationService revocation;
    private final AccountAuthorizationService accounts;
    private final AuditEventService audit;
    private final ConcurrentHashMap<String, Instant> lastAudit = new ConcurrentHashMap<>();

    public WebSocketOutboundAuthorizationInterceptor(
            WebSocketConnectionRegistry registry,
            AuthoritativeSessionValidator validator,
            WebSocketSessionRevocationService revocation,
            AccountAuthorizationService accounts,
            AuditEventService audit) {
        this.registry = registry;
        this.validator = validator;
        this.revocation = revocation;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String stompSessionId = accessor.getSessionId();
        String destination = safeDestination(accessor.getDestination());
        WebSocketConnectionRegistry.ConnectionRecord record =
                registry.byStompSession(stompSessionId);

        if (record == null) {
            return drop(null, "OUTBOUND_SESSION_MISSING", destination);
        }
        if (record.state() != WebSocketConnectionRegistry.State.AUTHENTICATED) {
            return drop(record, "OUTBOUND_SESSION_CLOSED", destination);
        }

        AuthoritativeSessionValidator.ValidationResult result = validator.validate(
                record.springSessionId(), record.userId().toString(),
                record.springSessionId(), false);
        AuthenticatedAccount account = accounts.resolve(record.principal()).orElse(null);
        if (!result.valid() || account == null
                || (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT)
                || account.role() != record.role()) {
            revocation.revokeConnection(record, reasonFor(result));
            return null;
        }

        SimpMessageType type = accessor.getMessageType();
        if (type != null && FRAMEWORK_MESSAGES.contains(type) && accessor.getDestination() == null) {
            return message;
        }
        if (accessor.getDestination() == null
                || !allowedDestination(accessor.getDestination(), record.stompSessionId())) {
            return drop(record, "OUTBOUND_DESTINATION_DENIED", destination);
        }
        if (!registry.hasSubscription(
                record, accessor.getDestination(), accessor.getSubscriptionId())) {
            return drop(record, "OUTBOUND_SUBSCRIPTION_MISSING", destination);
        }
        return message;
    }

    /**
     * Spring resolves {@code /user/queue/*} into a session-specific
     * {@code /queue/*-<stompSessionId>} destination before clientOutboundChannel
     * dispatch. Both forms are accepted only when they match a live subscription
     * on this exact connection; {@link #hasSubscription} remains the authority.
     */
    private static boolean allowedDestination(String destination, String stompSessionId) {
        if (ALLOWED_SUBSCRIPTIONS.contains(destination)) {
            return true;
        }
        if (stompSessionId == null) {
            return false;
        }
        for (String requested : ALLOWED_SUBSCRIPTIONS) {
            String base = "/" + requested.substring("/user/".length());
            String resolved = base + "-" + stompSessionId;
            // Spring's user-destination resolver prefixes the session id with
            // "user". Both forms remain bound to this exact session id.
            String springResolved = base + "-user" + stompSessionId;
            if (resolved.equals(destination) || springResolved.equals(destination)) {
                return true;
            }
        }
        return false;
    }

    private Message<?> drop(
            WebSocketConnectionRegistry.ConnectionRecord record,
            String reason, String destination) {
        throttleAndAudit(record == null ? null : record.userId(), reason, destination);
        return null;
    }

    private void throttleAndAudit(UUID userId, String reason, String destination) {
        if (lastAudit.size() > MAX_AUDIT_KEYS) {
            Instant now = Instant.now();
            lastAudit.forEach((key, time) -> {
                if (time.plusMillis(AUDIT_COOLDOWN_MS).isBefore(now)) {
                    lastAudit.remove(key);
                }
            });
        }
        String key = (userId == null ? "unknown" : userId) + "|" + reason;
        Instant now = Instant.now();
        Instant previous = lastAudit.putIfAbsent(key, now);
        if (previous != null && previous.plusMillis(AUDIT_COOLDOWN_MS).isAfter(now)) {
            return;
        }
        lastAudit.put(key, now);
        audit.recordWebSocketSecurityEvent(
                userId, AuditEventService.ACTION_WS_AUTH_FAILURE,
                reason, destination, UUID.randomUUID());
    }

    private static String reasonFor(AuthoritativeSessionValidator.ValidationResult result) {
        return switch (result.reason()) {
            case EXPIRED_SESSION -> WebSocketSessionRevocationService.REASON_SESSION_EXPIRED;
            case VALIDATION_FAILURE -> WebSocketSessionRevocationService.REASON_VALIDATION_FAILURE;
            default -> WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS;
        };
    }

    private static String safeDestination(String destination) {
        return destination != null && destination.matches("/[A-Za-z0-9/_-]{1,80}")
                ? destination : "unknown";
    }
}
