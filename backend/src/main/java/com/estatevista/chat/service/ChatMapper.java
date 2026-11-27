package com.estatevista.chat.service;

import java.util.Optional;

import com.estatevista.chat.dto.ConversationResponse;
import com.estatevista.chat.dto.MessageResponse;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.Message;

/** Maps entity state to the approved response shapes without exposing audit fields. */
final class ChatMapper {

    private ChatMapper() {
    }

    static MessageResponse toMessage(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getSender().getId(),
                message.getMessageType(),
                message.getContent(),
                message.getIdempotencyKey(),
                message.getSentAt());
    }

    static ConversationResponse toConversation(
            Conversation conversation, long unreadCount, Optional<Message> lastMessage) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getProperty().getId(),
                conversation.getProperty().getTitle(),
                conversation.getProperty().getStatus().name(),
                conversation.getBuyer().getId(),
                conversation.getAssignedAgent().getId(),
                unreadCount,
                lastMessage.map(Message::getId).orElse(null),
                lastMessage.map(Message::getSentAt).orElse(null),
                conversation.getCreatedAt());
    }
}
