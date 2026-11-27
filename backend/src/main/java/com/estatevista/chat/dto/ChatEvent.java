package com.estatevista.chat.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Application-level frames sent to {@code /user/queue/messages}. The native
 * STOMP RECEIPT is never treated as a persistence acknowledgement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
        String type,
        UUID id,
        UUID conversationId,
        UUID senderId,
        String messageType,
        String content,
        UUID idempotencyKey,
        Instant sentAt,
        String reason) {

    public static ChatEvent messageCreated(MessageResponse message) {
        return new ChatEvent(
                "MESSAGE_CREATED",
                message.id(),
                message.conversationId(),
                message.senderId(),
                message.messageType(),
                message.content(),
                message.idempotencyKey(),
                message.sentAt(),
                null);
    }

    public static ChatEvent persistenceAck(MessageResponse message) {
        // The approved ack carries only durable-acceptance correlation fields.
        // It never exposes sender, recipient, delivery or read state.
        return new ChatEvent(
                "PERSISTENCE_ACK",
                message.id(),
                message.conversationId(),
                null,
                null,
                null,
                message.idempotencyKey(),
                message.sentAt(),
                null);
    }

    public static ChatEvent error(UUID conversationId, UUID idempotencyKey, String reason) {
        return new ChatEvent(
                "MESSAGE_ERROR",
                null,
                conversationId,
                null,
                null,
                null,
                idempotencyKey,
                null,
                reason);
    }
}
