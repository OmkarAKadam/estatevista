package com.estatevista.chat.repository;

import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for per-participant conversation read state.
 */
public interface ConversationReadStateRepository
        extends JpaRepository<ConversationReadState, ConversationReadStateId> {
}
