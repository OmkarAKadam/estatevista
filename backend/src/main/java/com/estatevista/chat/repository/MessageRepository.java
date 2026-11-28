package com.estatevista.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.chat.entity.Message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for conversation messages.
 *
 * <p>Cursor predicates use the approved {@code (sent_at, id)} tie-breaker so
 * page boundaries remain stable when several commits share one timestamp.</p>
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** The V19 uniqueness authority for explicit client retries. */
    Optional<Message> findByConversationIdAndSenderIdAndIdempotencyKey(
            UUID conversationId, UUID senderId, UUID idempotencyKey);

    /** Cursor validation requires the encoded identity to exist in this conversation. */
    Optional<Message> findByConversationIdAndId(UUID conversationId, UUID messageId);

    /** Most recent visible message for conversation summaries. */
    Optional<Message> findFirstByConversationIdAndDeletedAtIsNullOrderBySentAtDescIdDesc(
            UUID conversationId);

    /** Latest persisted ordering position, read by the serialized send transaction. */
    @Query("""
            select max(m.sentAt) from Message m
            where m.conversation.id = :conversationId
            """)
    Optional<Instant> findLatestSentAt(@Param("conversationId") UUID conversationId);

    /** Newest page or an older page relative to an opaque cursor. */
    @Query("""
            select m from Message m
            where m.conversation.id = :conversationId
              and m.deletedAt is null
              and (
                :before = false
                or m.sentAt < :sentAt
                or (m.sentAt = :sentAt and m.id < :messageId)
              )
            order by m.sentAt desc, m.id desc
            """)
    List<Message> findLatestPage(
            @Param("conversationId") UUID conversationId,
            @Param("before") boolean before,
            @Param("sentAt") Instant sentAt,
            @Param("messageId") UUID messageId,
            Pageable pageable);

    /** Reconnection recovery page: messages newer than an opaque cursor. */
    @Query("""
            select m from Message m
            where m.conversation.id = :conversationId
              and m.deletedAt is null
              and (m.sentAt > :sentAt or (m.sentAt = :sentAt and m.id > :messageId))
            order by m.sentAt desc, m.id desc
            """)
    List<Message> findAfterPage(
            @Param("conversationId") UUID conversationId,
            @Param("sentAt") Instant sentAt,
            @Param("messageId") UUID messageId,
            Pageable pageable);

    /** Unread messages after a known viewed position, excluding the viewer's own. */
    @Query("""
            select count(m) from Message m
            where m.conversation.id = :conversationId
              and m.deletedAt is null
              and m.sender.id <> :userId
              and (
                m.sentAt > :sentAt
                or (m.sentAt = :sentAt and m.id > :messageId)
              )
            """)
    long countUnreadAfter(
            @Param("conversationId") UUID conversationId,
            @Param("userId") UUID userId,
            @Param("sentAt") Instant sentAt,
            @Param("messageId") UUID messageId);

    /** Unread messages when the participant has not advanced a read state. */
    @Query("""
            select count(m) from Message m
            where m.conversation.id = :conversationId
              and m.deletedAt is null
              and m.sender.id <> :userId
            """)
    long countUnread(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId);
}
