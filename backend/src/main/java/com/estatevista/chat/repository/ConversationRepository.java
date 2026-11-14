package com.estatevista.chat.repository;

import java.util.UUID;

import com.estatevista.chat.entity.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property conversations.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
}
