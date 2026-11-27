package com.estatevista.chat.dto;

/** Result of the idempotent get-or-create operation (API §11.1). */
public record CreatedConversation(ConversationResponse conversation, boolean created) {
}
