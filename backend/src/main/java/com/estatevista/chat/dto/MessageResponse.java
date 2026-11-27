package com.estatevista.chat.dto;

import java.time.Instant;
import java.util.UUID;

/** Message view shared by REST history and STOMP delivery (API §11.2/§20.5). */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String messageType,
        String content,
        UUID idempotencyKey,
        Instant sentAt) {
}
