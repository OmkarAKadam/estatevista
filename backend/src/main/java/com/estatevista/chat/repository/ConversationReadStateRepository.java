package com.estatevista.chat.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for per-participant conversation read state.
 *
 * <p>The user-leading index added by V19 backs {@link #findByConversationIdAndUserId}.</p>
 */
public interface ConversationReadStateRepository
        extends JpaRepository<ConversationReadState, ConversationReadStateId> {

    @Query("""
            select s from ConversationReadState s
            where s.conversation.id = :conversationId
              and s.user.id = :userId
            """)
    Optional<ConversationReadState> findByConversationIdAndUserId(
            @Param("conversationId") UUID conversationId,
            @Param("userId") UUID userId);
}
