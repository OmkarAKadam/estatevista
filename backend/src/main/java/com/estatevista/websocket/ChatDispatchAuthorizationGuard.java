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

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Chat-specific correction at the last outbound boundary.
 *
 * <p>The Stage 7.2 outbound interceptor revalidates the socket, account, role
 * and subscription when a frame enters {@code clientOutboundChannel}. This
 * guard runs after it, immediately before transport dispatch, and repeats the
 * live checks for chat events with conversation, participant, persisted-row
 * and sender-only acknowledgement authority. A failed recheck suppresses the
 * frame and writes a redacted security event; no message content is audited.</p>
 */
@Component
public final class ChatDispatchAuthorizationGuard implements ChannelInterceptor {

    private static final int MAX_AUDIT_KEYS = 512;
    private static final long AUDIT_COOLDOWN_MS = 30_000L;

    private final WebSocketConnectionRegistry registry;
    private final AuthoritativeSessionValidator validator;
    private final WebSocketSessionRevocationService revocation;
    private final AccountAuthorizationService accounts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Instant> lastAudit = new ConcurrentHashMap<>();

    public ChatDispatchAuthorizationGuard(
            WebSocketConnectionRegistry registry,
            AuthoritativeSessionValidator validator,
            WebSocketSessionRevocationService revocation,
            AccountAuthorizationService accounts,
            ConversationRepository conversations,
            MessageRepository messages,
            AuditEventService audit,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.validator = validator;
        this.revocation = revocation;
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    public org.springframework.messaging.Message<?> preSend(
            org.springframework.messaging.Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        if (!isMessagesQueue(destination)) {
            return message;
        }

        String stompSessionId = accessor.getSessionId();
        WebSocketConnectionRegistry.ConnectionRecord record =
                stompSessionId == null ? null : registry.byStompSession(stompSessionId);
        if (record == null) {
            return drop(null, "DISPATCH_SESSION_MISSING", destination);
        }
        if (record.state() != WebSocketConnectionRegistry.State.AUTHENTICATED) {
            return drop(record, "DISPATCH_SESSION_CLOSED", destination);
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
        if (!registry.hasSubscription(record, destination, accessor.getSubscriptionId())) {
            return drop(record, "DISPATCH_SUBSCRIPTION_MISSING", destination);
        }
        if (!matchesResolvedDestination(destination, stompSessionId)) {
            return drop(record, "DISPATCH_DESTINATION_MISMATCH", destination);
        }

        Map<String, Object> event = parseEvent(message.getPayload());
        if (event == null) {
            return drop(record, "DISPATCH_CHAT_EVENT_INVALID", destination);
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
            return drop(record, "DISPATCH_CHAT_EVENT_INVALID", destination);
        }
        if (conversations.findParticipantConversation(
                conversationId, account.id(), account.role() == PlatformRole.BUYER).isEmpty()) {
            return drop(record, "DISPATCH_CONVERSATION_DENIED", destination);
        }

        Message persisted = messages.findByConversationIdAndId(conversationId, messageId).orElse(null);
        if (persisted == null || persisted.getDeletedAt() != null
                || !persisted.getIdempotencyKey().equals(idempotencyKey)) {
            return drop(record, "DISPATCH_MESSAGE_MISMATCH", destination);
        }
        if (ack) {
            // The ack has no sender field by contract, so the persisted sender is
            // compared with the resolved destination account. A stale or forged
            // recipient-targeted ack is suppressed at this final boundary.
            if (!persisted.getSender().getId().equals(account.id())) {
                return drop(record, "DISPATCH_ACK_NOT_SENDER", destination);
            }
        } else if (!persisted.getSender().getId().equals(eventSenderId)) {
            return drop(record, "DISPATCH_MESSAGE_MISMATCH", destination);
        }
        if (!ack && event.get("content") != null
                && !persisted.getContent().equals(event.get("content"))) {
            return drop(record, "DISPATCH_MESSAGE_MISMATCH", destination);
        }
        return message;
    }

    private org.springframework.messaging.Message<?> drop(
            WebSocketConnectionRegistry.ConnectionRecord record,
            String reason, String destination) {
        UUID userId = record == null ? null : record.userId();
        String key = (userId == null ? "unknown" : userId) + "|" + reason;
        Instant now = Instant.now();
        if (lastAudit.size() > MAX_AUDIT_KEYS) {
            lastAudit.entrySet().removeIf(entry ->
                    entry.getValue().plusMillis(AUDIT_COOLDOWN_MS).isBefore(now));
        }
        Instant previous = lastAudit.putIfAbsent(key, now);
        if (previous == null || previous.plusMillis(AUDIT_COOLDOWN_MS).isBefore(now)) {
            lastAudit.put(key, now);
            audit.recordWebSocketSecurityEvent(
                    userId, AuditEventService.ACTION_WS_AUTH_FAILURE,
                    reason, safeDestination(destination), UUID.randomUUID());
        }
        return null;
    }

    private Map<String, Object> parseEvent(Object payload) {
        try {
            String text;
            if (payload instanceof byte[] bytes) {
                text = new String(bytes, StandardCharsets.UTF_8);
            } else if (payload instanceof String value) {
                text = value;
            } else {
                text = objectMapper.writeValueAsString(payload);
            }
            Object parsed = objectMapper.readValue(text, Object.class);
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

    private static boolean isMessagesQueue(String destination) {
        if (destination == null) {
            return false;
        }
        int suffix = destination.lastIndexOf('/');
        if (suffix < 0) {
            return false;
        }
        String queue = destination.substring(suffix + 1);
        int sessionSeparator = queue.indexOf('-');
        return sessionSeparator > 0 && "messages".equals(queue.substring(0, sessionSeparator));
    }

    private static boolean matchesResolvedDestination(String destination, String stompSessionId) {
        if (stompSessionId == null) {
            return false;
        }
        // Spring 7 resolves /user/queue/messages to /queue/messages-user<id>;
        // the unprefixed form is retained for compatibility with older brokers.
        return ("/queue/messages-" + stompSessionId).equals(destination)
                || ("/queue/messages-user" + stompSessionId).equals(destination);
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
