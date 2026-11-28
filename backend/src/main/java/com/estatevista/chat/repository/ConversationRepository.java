package com.estatevista.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.chat.entity.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for property conversations.
 *
 * <p>Every read path that is available to a participant is expressed as a
 * participant-scoped query. An unscoped {@code findById} is never used by a
 * controller or service for private conversation data.</p>
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** The buyer-facing permanent owner lookup used by get-or-create. */
    Optional<Conversation> findByBuyerIdAndPropertyId(UUID buyerId, UUID propertyId);

    /** Agent history is scoped by the immutable original assignment. */
    List<Conversation> findAllByAssignedAgentIdOrderByCreatedAtDescIdDesc(UUID assignedAgentId);

    /** Buyer history is scoped by the immutable buyer reference. */
    List<Conversation> findAllByBuyerIdOrderByCreatedAtDescIdDesc(UUID buyerId);

    /**
     * Participant-scoped detail lookup. The role condition is checked by the
     * service against the trusted account before this query is used.
     */
    @Query("""
            select c from Conversation c
            where c.id = :conversationId
              and (
                (:buyerOnly = true and c.buyer.id = :userId)
                or (:buyerOnly = false and c.assignedAgent.id = :userId)
              )
            """)
    Optional<Conversation> findParticipantConversation(
            @Param("conversationId") UUID conversationId,
            @Param("userId") UUID userId,
            @Param("buyerOnly") boolean buyerOnly);
}
