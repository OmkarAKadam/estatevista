package com.estatevista.websocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.config.AllowedOrigins;
import com.estatevista.config.AppProperties;
import com.estatevista.config.WebSocketSecurityProperties;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Protected inbound STOMP boundary: CONNECT CSRF, session and frame authorization. */
@Component
public final class WebSocketSecurityInterceptor implements ChannelInterceptor {

    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages", "/user/queue/notifications", "/user/queue/errors");
    private static final String SEND_DESTINATION = "/app/chat.send";
    private static final Pattern CONVERSATION_ID =
            Pattern.compile("\"conversationId\"\\s*:\\s*\"([^\"]+)\"");
    private static final int MAX_AUDIT_KEYS = 512;
    private static final long AUDIT_COOLDOWN_MS = 30_000L;

    private final WebSocketSecurityProperties securityProperties;
    private final AuthoritativeSessionValidator validator;
    private final WebSocketConnectionRegistry registry;
    private final WebSocketSessionRevocationService revocation;
    private final WebSocketRateLimitService rateLimits;
    private final AccountAuthorizationService accounts;
    private final AuditEventService audit;
    private final List<String> allowedOrigins;
    private final ConcurrentHashMap<String, Instant> lastAudit = new ConcurrentHashMap<>();

    public WebSocketSecurityInterceptor(
            AppProperties properties,
            WebSocketSecurityProperties securityProperties,
            AuthoritativeSessionValidator validator,
            WebSocketConnectionRegistry registry,
            WebSocketSessionRevocationService revocation,
            WebSocketRateLimitService rateLimits,
            AccountAuthorizationService accounts,
            AuditEventService audit) {
        this.securityProperties = securityProperties;
        this.validator = validator;
        this.registry = registry;
        this.revocation = revocation;
        this.rateLimits = rateLimits;
        this.accounts = accounts;
        this.audit = audit;
        this.allowedOrigins = AllowedOrigins.requireExplicit(
                properties.websocket().allowedOrigins(), "app.websocket.allowed-origins");
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        accessor.setLeaveMutable(true);
        StompCommand command = accessor.getCommand();
        Map<String, Object> attributes = accessor.getSessionAttributes();
        String handshakeId = attributes == null
                ? null : (String) attributes.get(WebSocketSessionAttributes.HANDSHAKE_ID);
        WebSocketConnectionRegistry.ConnectionRecord record = registry.byHandshake(handshakeId);
        String destination = safeDestination(accessor.getDestination());

        try {
            enforceFrameSize(message, record);
            if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
                authorizeConnect(accessor, record, handshakeId, attributes);
                return withAccessorHeaders(message, accessor);
            }
            if (record == null || record.state() != WebSocketConnectionRegistry.State.AUTHENTICATED) {
                // A revoked transport emits a final DISCONNECT from the STOMP
                // layer. Dropping it avoids noisy rejections of an already
                // closed session without re-opening the security boundary.
                if (command == StompCommand.DISCONNECT) {
                    return null;
                }
                throw denied("NOT_AUTHENTICATED", destination, record);
            }
            if (command == null) {
                // Native STOMP heartbeat or transport lifecycle activity. The
                // authoritative session is rechecked with the bounded cache.
                requireValidSession(record, true, destination);
                registry.markActivity(record);
                return withAccessorHeaders(message, accessor);
            }
            switch (command) {
                case SUBSCRIBE -> {
                    requireValidSession(record, false, destination);
                    String requested = accessor.getDestination();
                    if (requested == null || !ALLOWED_SUBSCRIPTIONS.contains(requested)) {
                        throw denied("DESTINATION_NOT_ALLOWED", destination, record);
                    }
                    if (!registry.addSubscription(
                            record, requested, accessor.getSubscriptionId())) {
                        throw denied("SUBSCRIPTION_LIMIT", destination, record);
                    }
                    registry.markActivity(record);
                }
                case SEND -> {
                    requireValidSession(record, false, destination);
                    if (!SEND_DESTINATION.equals(accessor.getDestination())) {
                        throw denied("DESTINATION_NOT_ALLOWED", destination, record);
                    }
                    rateLimits.acquireUser(record.userId());
                    UUID conversationId = conversationIdOf(message.getPayload());
                    if (conversationId != null) {
                        rateLimits.acquireConversation(record.userId(), conversationId);
                    }
                    registry.markActivity(record);
                }
                case UNSUBSCRIBE -> {
                    requireValidSession(record, false, destination);
                    registry.removeSubscription(record, accessor.getSubscriptionId());
                    registry.markActivity(record);
                }
                case DISCONNECT -> {
                    registry.clearSubscriptions(record);
                    registry.markActivity(record);
                }
                default -> throw denied("COMMAND_NOT_ALLOWED", destination, record);
            }
            return withAccessorHeaders(message, accessor);
        } catch (RuntimeException exception) {
            // A boundary violation terminates the transport. Relying only on the
            // messaging exception would leave an authenticated socket open while
            // its registry state was already removed.
            if (record != null) {
                revocation.revokeConnection(record, reasonOf(exception));
            }
            recordDenial(record, reasonOf(exception), scopeOf(exception, destination));
            throw exception;
        }
    }

    private void authorizeConnect(
            StompHeaderAccessor accessor,
            WebSocketConnectionRegistry.ConnectionRecord record,
            String handshakeId,
            Map<String, Object> attributes) {
        if (record == null || handshakeId == null || attributes == null) {
            throw denied("NOT_AUTHENTICATED", "connect", null);
        }
        if (record.state() != WebSocketConnectionRegistry.State.PENDING) {
            throw denied("DUPLICATE_CONNECT", "connect", record);
        }
        Object origin = attributes.get(WebSocketSessionAttributes.ORIGIN);
        if (!(origin instanceof String value) || !allowedOrigins.contains(value)) {
            throw denied("INVALID_ORIGIN", "connect", record);
        }
        List<String> tokens = csrfHeaders(accessor);
        String expected = validator.csrfToken(record.springSessionId());
        if (tokens.size() != 1 || expected == null
                || !constantTimeEquals(expected, tokens.get(0))) {
            throw denied("INVALID_CONNECT_CSRF", "connect", record);
        }

        AuthoritativeSessionValidator.ValidationResult sessionResult = validator.validate(
                record.springSessionId(), record.userId().toString(),
                record.springSessionId(), false);
        AuthenticatedAccount account = accounts.resolve(record.principal()).orElse(null);
        if (!sessionResult.valid() || account == null
                || (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT)) {
            throw denied("AUTHORIZATION_LOSS", "connect", record);
        }

        String stompSessionId = accessor.getSessionId();
        if (stompSessionId == null && record.transport() != null) {
            stompSessionId = record.transport().getId();
        }
        WebSocketConnectionRegistry.ConnectionRecord activated = registry.activate(
                handshakeId, stompSessionId, account.role());
        if (activated == null) {
            throw denied("CONNECTION_LIMIT", "connect", record);
        }
        // The trusted handshake principal is installed on the STOMP session so
        // /user/** destinations resolve to the authenticated account only.
        accessor.setUser(record.principal());
    }

    private void requireValidSession(
            WebSocketConnectionRegistry.ConnectionRecord record,
            boolean allowCached,
            String destination) {
        AuthoritativeSessionValidator.ValidationResult result = validator.validate(
                record.springSessionId(), record.userId().toString(),
                record.springSessionId(), allowCached);
        AuthenticatedAccount account = accounts.resolve(record.principal()).orElse(null);
        if (!result.valid() || account == null
                || (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT)
                || account.role() != record.role()) {
            revocation.revokeConnection(record, reasonFor(result));
            throw denied("AUTHORIZATION_LOSS", destination, record);
        }
    }

    private void enforceFrameSize(
            Message<?> message, WebSocketConnectionRegistry.ConnectionRecord record) {
        long payloadBytes = switch (message.getPayload()) {
            case String text -> text.getBytes(StandardCharsets.UTF_8).length;
            case byte[] bytes -> bytes.length;
            case null -> 0L;
            default -> message.getPayload().toString().getBytes(StandardCharsets.UTF_8).length;
        };
        long headerBytes = message.getHeaders().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof String text)
                .mapToLong(entry -> ((String) entry.getValue()).getBytes(StandardCharsets.UTF_8).length)
                .sum();
        if (payloadBytes + headerBytes > securityProperties.getMaxFrameBytes()) {
            if (record != null) {
                revocation.revokeConnection(record, WebSocketSessionRevocationService.REASON_CONNECTION_LIMIT);
            }
            throw denied("FRAME_TOO_LARGE", "frame", record);
        }
    }

    /**
     * Returns a message carrying the accessor's mutations. This is required so
     * the trusted handshake principal installed on CONNECT actually reaches the
     * broker rather than being discarded with a copied header map.
     */
    private static Message<?> withAccessorHeaders(
            Message<?> original, StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(original.getPayload(), accessor.getMessageHeaders());
    }

    private static List<String> csrfHeaders(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader("X-CSRF-TOKEN");
        return values == null ? List.of() : List.copyOf(values);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null || supplied.length() != expected.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID conversationIdOf(Object payload) {
        String text = switch (payload) {
            case String value -> value;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> null;
        };
        if (text == null || text.length() > 4096) {
            return null;
        }
        Matcher matcher = CONVERSATION_ID.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void recordDenial(
            WebSocketConnectionRegistry.ConnectionRecord record,
            String reason, String destination) {
        if (lastAudit.size() > MAX_AUDIT_KEYS) {
            Instant now = Instant.now();
            lastAudit.forEach((key, time) -> {
                if (time.plusMillis(AUDIT_COOLDOWN_MS).isBefore(now)) {
                    lastAudit.remove(key);
                }
            });
        }
        String key = (record == null ? "pending" : record.connectionId()) + "|" + reason;
        Instant now = Instant.now();
        Instant previous = lastAudit.putIfAbsent(key, now);
        if (previous != null && previous.plusMillis(AUDIT_COOLDOWN_MS).isAfter(now)) {
            return;
        }
        lastAudit.put(key, now);
        audit.recordWebSocketSecurityEvent(
                record == null ? null : record.userId(),
                AuditEventService.ACTION_WS_AUTH_FAILURE,
                reason, destination, UUID.randomUUID());
    }

    private static String reasonOf(RuntimeException exception) {
        for (Throwable suppressed : exception.getSuppressed()) {
            if (suppressed instanceof DenialReason denial) {
                return denial.reason();
            }
        }
        return "DENIED";
    }

    /** Uses the denial's own classification, not an incidental frame destination. */
    private static String scopeOf(RuntimeException exception, String fallback) {
        for (Throwable suppressed : exception.getSuppressed()) {
            if (suppressed instanceof DenialReason denial) {
                return denial.getMessage();
            }
        }
        return fallback;
    }

    private static String reasonFor(AuthoritativeSessionValidator.ValidationResult result) {
        return switch (result.reason()) {
            case EXPIRED_SESSION -> WebSocketSessionRevocationService.REASON_SESSION_EXPIRED;
            case VALIDATION_FAILURE -> WebSocketSessionRevocationService.REASON_VALIDATION_FAILURE;
            default -> WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS;
        };
    }

    private static AccessDeniedException denied(
            String reason, String destination,
            WebSocketConnectionRegistry.ConnectionRecord record) {
        AccessDeniedException exception = new AccessDeniedException("Messaging access is denied");
        exception.addSuppressed(new DenialReason(reason, destination, record));
        return exception;
    }

    private static String safeDestination(String destination) {
        return destination != null && destination.matches("/[A-Za-z0-9/_-]{1,80}")
                ? destination : "unknown";
    }

    /** Internal marker used only to carry an audit-safe reason to the denial handler. */
    private static final class DenialReason extends RuntimeException {
        private final String reason;

        DenialReason(
                String reason, String destination,
                WebSocketConnectionRegistry.ConnectionRecord record) {
            super(destination);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
