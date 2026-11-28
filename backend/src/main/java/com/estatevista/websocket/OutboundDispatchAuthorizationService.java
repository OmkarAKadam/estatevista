package com.estatevista.websocket;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;

import org.springframework.stereotype.Component;

import com.estatevista.websocket.WebSocketConnectionRegistry.ConnectionRecord;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared authorization decisions for protected outbound STOMP frames.
 *
 * <p>The same decision logic is applied when a frame enters the outbound
 * channel and again by the authorized transport session after the executor has
 * dequeued it. The service never revokes while a connection read lock is held:
 * it returns a decision and the caller closes the connection after releasing
 * the lock.</p>
 */
@Component
public final class OutboundDispatchAuthorizationService {

    public enum Boundary {
        CHANNEL("DISPATCH_"),
        TRANSPORT("TRANSPORT_");

        private final String prefix;

        Boundary(String prefix) {
            this.prefix = prefix;
        }

        String prefix() {
            return prefix;
        }
    }

    public enum Outcome {
        ALLOW,
        DROP,
        REVOKE
    }

    public record Decision(Outcome outcome, String reason, String revocationReason) {
        static Decision allow() {
            return new Decision(Outcome.ALLOW, null, null);
        }

        static Decision revoke(String reason) {
            return new Decision(Outcome.REVOKE, null, reason);
        }
    }

    /** Boundary-neutral view of an outbound private STOMP MESSAGE frame. */
    public record Frame(String destination, String subscriptionId, String body) {
    }

    private static final Map<String, String> PRIVATE_QUEUES = Map.of(
            "/user/queue/messages", "messages",
            "/user/queue/notifications", "notifications",
            "/user/queue/errors", "errors");
    private static final int MAX_AUDIT_KEYS = 512;
    private static final long AUDIT_COOLDOWN_MS = 30_000L;

    private final WebSocketConnectionRegistry registry;
    private final AuthoritativeSessionValidator validator;
    private final AccountAuthorizationService accounts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Instant> lastAudit = new ConcurrentHashMap<>();

    public OutboundDispatchAuthorizationService(
            WebSocketConnectionRegistry registry,
            AuthoritativeSessionValidator validator,
            AccountAuthorizationService accounts,
            ConversationRepository conversations,
            MessageRepository messages,
            AuditEventService audit,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.validator = validator;
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** Acquires the connection read lock for a channel-level check. */
    public Decision authorize(Boundary boundary, ConnectionRecord record, Frame frame) {
        if (record == null) {
            return drop(boundary, null, "SESSION_MISSING", frame.destination());
        }
        record.readLock().lock();
        try {
            return authorizeLocked(boundary, record, frame);
        } finally {
            record.readLock().unlock();
        }
    }

    /**
     * Authorization for a caller that already holds the connection read lock.
     * The transport decorator keeps that lock held through the real write, so a
     * revocation cannot close the transport halfway through authorization.
     */
    public Decision authorizeLocked(Boundary boundary, ConnectionRecord record, Frame frame) {
        String destination = frame.destination();
        String queue = privateQueue(destination, record.stompSessionId());
        if (queue == null) {
            return Decision.allow();
        }
        if (record.state() != WebSocketConnectionRegistry.State.AUTHENTICATED) {
            return drop(boundary, record, "SESSION_CLOSED", destination);
        }

        AuthoritativeSessionValidator.ValidationResult result = validator.validate(
                record.springSessionId(), record.userId().toString(),
                record.springSessionId(), false);
        AuthenticatedAccount account = accounts.resolve(record.principal()).orElse(null);
        if (!result.valid() || account == null
                || (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT)
                || account.role() != record.role()) {
            return Decision.revoke(reasonFor(result));
        }
        if (!registry.hasSubscription(record, destination, frame.subscriptionId())) {
            return drop(boundary, record, "SUBSCRIPTION_MISSING", destination);
        }
        if (!matchesResolvedDestination(destination, record.stompSessionId(), queue)) {
            return drop(boundary, record, "DESTINATION_MISMATCH", destination);
        }
        if ("messages".equals(queue)) {
            return authorizeChatEvent(boundary, record, account, frame.body(), destination);
        }
        return Decision.allow();
    }

    /** Converts a Spring message payload into the JSON text used by both boundaries. */
    public String payloadText(Object payload) {
        try {
            if (payload instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (payload instanceof String text) {
                return text;
            }
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Decision authorizeChatEvent(
            Boundary boundary, ConnectionRecord record, AuthenticatedAccount account,
            String body, String destination) {
        Map<String, Object> event = parseEvent(body);
        if (event == null) {
            return drop(boundary, record, "CHAT_EVENT_INVALID", destination);
        }
        String type = string(event.get("type"));
        UUID conversationId = uuid(event.get("conversationId"));
        UUID messageId = uuid(event.get("id"));
        UUID eventSenderId = uuid(event.get("senderId"));
        UUID idempotencyKey = uuid(event.get("idempotencyKey"));
        boolean ack = "PERSISTENCE_ACK".equals(type);
        if (!("MESSAGE_CREATED".equals(type) || ack)
                || conversationId == null || messageId == null
                || idempotencyKey == null
                || (!ack && eventSenderId == null)
                || (ack && eventSenderId != null)) {
            // MESSAGE_CREATED requires the participant-visible sender field;
            // PERSISTENCE_ACK must contain no sender/recipient information.
            return drop(boundary, record, "CHAT_EVENT_INVALID", destination);
        }
        if (conversations.findParticipantConversation(
                conversationId, account.id(), account.role() == PlatformRole.BUYER).isEmpty()) {
            return drop(boundary, record, "CONVERSATION_DENIED", destination);
        }

        Message persisted = messages.findByConversationIdAndId(conversationId, messageId).orElse(null);
        if (persisted == null || persisted.getDeletedAt() != null
                || !persisted.getIdempotencyKey().equals(idempotencyKey)) {
            return drop(boundary, record, "MESSAGE_MISMATCH", destination);
        }
        if (ack) {
            // The ack has no sender field by contract, so the persisted sender is
            // compared with the resolved destination account. A stale or
            // recipient-targeted ack is suppressed at the final boundary.
            if (!persisted.getSender().getId().equals(account.id())) {
                return drop(boundary, record, "ACK_NOT_SENDER", destination);
            }
        } else if (!persisted.getSender().getId().equals(eventSenderId)) {
            return drop(boundary, record, "MESSAGE_MISMATCH", destination);
        }
        if (!ack && event.get("content") != null
                && !persisted.getContent().equals(event.get("content"))) {
            return drop(boundary, record, "MESSAGE_MISMATCH", destination);
        }
        return Decision.allow();
    }

    private Decision drop(
            Boundary boundary, ConnectionRecord record, String baseReason, String destination) {
        String reason = boundary.prefix() + baseReason;
        UUID userId = record == null ? null : record.userId();
        Instant now = Instant.now();
        if (lastAudit.size() > MAX_AUDIT_KEYS) {
            lastAudit.entrySet().removeIf(entry ->
                    entry.getValue().plusMillis(AUDIT_COOLDOWN_MS).isBefore(now));
        }
        String key = (userId == null ? "unknown" : userId) + "|" + reason;
        Instant previous = lastAudit.putIfAbsent(key, now);
        if (previous == null || previous.plusMillis(AUDIT_COOLDOWN_MS).isBefore(now)) {
            lastAudit.put(key, now);
            audit.recordWebSocketSecurityEvent(
                    userId, AuditEventService.ACTION_WS_AUTH_FAILURE,
                    reason, safeDestination(destination), UUID.randomUUID());
        }
        return new Decision(Outcome.DROP, reason, null);
    }

    private Map<String, Object> parseEvent(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            raw.forEach((key, value) -> event.put(String.valueOf(key), value));
            return event;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String privateQueue(String destination, String stompSessionId) {
        if (destination == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : PRIVATE_QUEUES.entrySet()) {
            String requested = entry.getKey();
            if (requested.equals(destination)) {
                return entry.getValue();
            }
            String base = "/" + requested.substring("/user/".length());
            if (stompSessionId != null
                    && ((base + "-" + stompSessionId).equals(destination)
                    || (base + "-user" + stompSessionId).equals(destination))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean matchesResolvedDestination(
            String destination, String stompSessionId, String queue) {
        if (destination == null || stompSessionId == null) {
            return false;
        }
        String base = "/queue/" + queue;
        return ("/user" + base).equals(destination)
                || (base + "-" + stompSessionId).equals(destination)
                || (base + "-user" + stompSessionId).equals(destination);
    }

    private static String safeDestination(String destination) {
        return destination != null && destination.matches("/[A-Za-z0-9/_-]{1,80}")
                ? destination : "unknown";
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static UUID uuid(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String reasonFor(AuthoritativeSessionValidator.ValidationResult result) {
        return switch (result.reason()) {
            case EXPIRED_SESSION -> WebSocketSessionRevocationService.REASON_SESSION_EXPIRED;
            case VALIDATION_FAILURE -> WebSocketSessionRevocationService.REASON_VALIDATION_FAILURE;
            default -> WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS;
        };
    }
}
