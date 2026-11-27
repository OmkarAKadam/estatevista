package com.estatevista.chat.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant-scoped conversation view (API §11.1). Other-participant read
 * positions and message content are deliberately absent.
 */
public record ConversationResponse(
        UUID conversationId,
        UUID propertyId,
        String propertyTitle,
        String propertyStatus,
        UUID buyerId,
        UUID assignedAgentId,
        long unreadCount,
        UUID lastMessageId,
        Instant lastMessageAt,
        Instant createdAt) {
}
