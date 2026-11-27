package com.estatevista.chat.controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.chat.dto.ChatEvent;
import com.estatevista.chat.dto.ChatSendRequest;
import com.estatevista.chat.service.ChatMessageService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import tools.jackson.databind.ObjectMapper;

/**
 * The single approved STOMP mutation: {@code /app/chat.send}.
 *
 * <p>The handler validates the exact payload, derives the sender from the
 * authenticated connection, delegates persistence to the transactional
 * service, and publishes user-queue frames only after that transaction has
 * committed. Native STOMP RECEIPT is never treated as an application
 * acknowledgement. Every rejection writes content-free audit metadata before
 * emitting a safe error frame. Spring's STOMP annotation handler detects only
 * {@code @Controller} beans, so this stereotype is required even though no HTTP
 * route is exposed.</p>
 */
@Controller
public class ChatSendController {

    private static final Logger log = LoggerFactory.getLogger(ChatSendController.class);
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("conversationId", "content", "idempotencyKey");
    private static final String MESSAGES_QUEUE = "/queue/messages";
    private static final String ERRORS_QUEUE = "/queue/errors";
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final ChatMessageService messages;
    private final AccountAuthorizationService accounts;
    private final AuditEventService audit;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    public ChatSendController(
            ChatMessageService messages,
            AccountAuthorizationService accounts,
            AuditEventService audit,
            SimpMessagingTemplate messaging,
            ObjectMapper objectMapper) {
        this.messages = messages;
        this.accounts = accounts;
        this.audit = audit;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
    }

    @MessageMapping("/chat.send")
    public void send(@Payload byte[] payload, Principal principal) {
        String body = payload == null ? null : new String(payload, StandardCharsets.UTF_8);
        String user = principal == null ? null : principal.getName();
        Map<String, Object> fields = safeFields(body);
        UUID conversationId = uuid(fields.get("conversationId"));
        UUID idempotencyKey = uuid(fields.get("idempotencyKey"));
        UUID actorId = uuid(user);

        if (user == null || user.isBlank()) {
            reject(user, actorId, conversationId, idempotencyKey, "UNAUTHENTICATED");
            return;
        }

        try {
            AuthenticatedAccount account = accounts.requireActivePrincipal(principal);
            actorId = account.id();
            ChatSendRequest request = parse(body);
            validateShape(request);
            ChatMessageService.SendResult result = messages.send(account, request);
            publishAfterCommit(user, result);
        } catch (ApiException exception) {
            reject(user, actorId, conversationId, idempotencyKey, reasonFor(exception));
        } catch (RuntimeException exception) {
            // Never log the payload: it can contain private message content.
            log.warn("Rejected chat send: {}", exception.getClass().getSimpleName());
            reject(user, actorId, conversationId, idempotencyKey, "PERSISTENCE_FAILED");
        }
    }

    private void publishAfterCommit(String sender, ChatMessageService.SendResult result) {
        ChatEvent created = ChatEvent.messageCreated(result.message());
        if (result.created()) {
            publish(sender, created, "MESSAGE_CREATED to sender");
            publish(result.recipientId().toString(), created, "MESSAGE_CREATED to recipient");
        }
        publish(sender, ChatEvent.persistenceAck(result.message()), "PERSISTENCE_ACK to sender");
    }

    private void publish(String user, ChatEvent event, String description) {
        try {
            messaging.convertAndSendToUser(user, MESSAGES_QUEUE, event);
        } catch (RuntimeException exception) {
            // The mutation is already durable. Recovery is REST history plus an
            // explicit client retry with the same key; nothing is replayed here.
            log.warn("Failed to publish {}: {}", description, exception.getClass().getSimpleName());
        }
    }

    private void reject(
            String user,
            UUID actorId,
            UUID conversationId,
            UUID idempotencyKey,
            String reason) {
        audit.recordMessageRejected(
                actorId, conversationId, null, idempotencyKey, reason, UUID.randomUUID());
        if (user != null && !user.isBlank()) {
            publishError(user, conversationId, idempotencyKey, reason);
        }
    }

    private void publishError(
            String user, UUID conversationId, UUID idempotencyKey, String reason) {
        try {
            messaging.convertAndSendToUser(
                    user,
                    ERRORS_QUEUE,
                    ChatEvent.error(conversationId, idempotencyKey, reason));
        } catch (RuntimeException exception) {
            log.warn("Failed to publish chat error: {}", exception.getClass().getSimpleName());
        }
    }

    private ChatSendRequest parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw invalid("INVALID_PAYLOAD");
        }
        Map<String, Object> fields;
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw invalid("INVALID_PAYLOAD");
            }
            fields = new java.util.LinkedHashMap<>();
            raw.forEach((key, value) -> fields.put(String.valueOf(key), value));
        } catch (RuntimeException exception) {
            throw invalid("INVALID_PAYLOAD");
        }

        ChatSendRequest request = new ChatSendRequest();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!ALLOWED_FIELDS.contains(entry.getKey())) {
                request.captureUnknownField(entry.getKey(), null);
            }
        }
        request.setConversationId(uuid(fields.get("conversationId")));
        request.setIdempotencyKey(uuid(fields.get("idempotencyKey")));
        Object content = fields.get("content");
        request.setContent(content instanceof String value ? value : null);
        return request;
    }

    private static void validateShape(ChatSendRequest request) {
        if (!request.unknownFields().isEmpty()) {
            throw invalid("UNKNOWN_FIELD");
        }
        if (request.getConversationId() == null || request.getIdempotencyKey() == null) {
            throw invalid("INVALID_IDENTIFIER");
        }
        String content = request.getContent();
        if (content == null || content.isBlank()) {
            throw invalid("CONTENT_REQUIRED");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw invalid("CONTENT_TOO_LONG");
        }
    }

    private Map<String, Object> safeFields(String payload) {
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return Map.of();
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            raw.forEach((key, value) -> fields.put(String.valueOf(key), value));
            return fields;
        } catch (RuntimeException exception) {
            return Map.of();
        }
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

    private static String reasonFor(ApiException exception) {
        String message = exception.getMessage();
        if (exception.getErrorCode() == ErrorCode.VALIDATION_ERROR
                && message != null && message.matches("[A-Z_]{1,32}")) {
            return message;
        }
        return switch (exception.getErrorCode()) {
            case BAD_REQUEST, VALIDATION_ERROR -> "INVALID_PAYLOAD";
            case CONFLICT -> "IDEMPOTENCY_CONFLICT";
            case NOT_FOUND, FORBIDDEN, UNAUTHORIZED -> "MESSAGE_DENIED";
            default -> "PERSISTENCE_FAILED";
        };
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, reason);
    }
}
