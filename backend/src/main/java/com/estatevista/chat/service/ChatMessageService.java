package com.estatevista.chat.service;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.chat.dto.ChatSendRequest;
import com.estatevista.chat.dto.MessagePageResponse;
import com.estatevista.chat.dto.MessageResponse;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.notification.entity.Notification;
import com.estatevista.notification.repository.NotificationRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;

import jakarta.persistence.EntityManager;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional message send and participant-scoped cursor history.
 *
 * <p>The send transaction serializes inserts per conversation, derives the
 * sender from the trusted account, assigns {@code sentAt} from the database
 * clock after obtaining the lock, relies on the V19 uniqueness constraint for
 * retries, and writes audit/notification rows in the same transaction. Events
 * are published only after this transaction commits.</p>
 */
@Service
public class ChatMessageService {

    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    private final AccountAuthorizationService accounts;
    private final ConversationService conversations;
    private final MessageRepository messages;
    private final NotificationRepository notifications;
    private final AuditEventService audit;
    private final EntityManager entityManager;
    private final TransactionTemplate sendTemplate;

    public ChatMessageService(
            AccountAuthorizationService accounts,
            ConversationService conversations,
            MessageRepository messages,
            NotificationRepository notifications,
            AuditEventService audit,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.sendTemplate = new TransactionTemplate(transactionManager);
        this.sendTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Validated and persisted message result. {@code created} is false for an
     * exact retry. Participant IDs are server-derived and are used only for
     * post-commit per-user routing; clients never supply them.
     */
    public record SendResult(
            MessageResponse message,
            boolean created,
            UUID conversationId,
            UUID senderId,
            UUID recipientId) {
    }

    public SendResult send(AuthenticatedAccount account, ChatSendRequest request) {
        AuthenticatedAccount trusted = account != null ? account : throwUnauthorized();
        validate(request);

        try {
            return sendTemplate.execute(status -> persist(trusted, request));
        } catch (RuntimeException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            Message existing = messages.findByConversationIdAndSenderIdAndIdempotencyKey(
                            request.getConversationId(), trusted.id(), request.getIdempotencyKey())
                    .orElseThrow(ChatMessageService::conflict);
            if (!request.getContent().equals(existing.getContent())) {
                throw conflict();
            }
            return new SendResult(
                    ChatMapper.toMessage(existing),
                    false,
                    existing.getConversation().getId(),
                    trusted.id(),
                    otherParticipant(existing.getConversation(), trusted.id()));
        }
    }

    @Transactional(readOnly = true)
    public MessagePageResponse history(
            Authentication authentication,
            UUID conversationId,
            int limit,
            String before,
            String after) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        if (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT) {
            throw denied();
        }
        conversations.requireConversation(account, conversationId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "limit must be between 1 and 100");
        }
        if (before != null && after != null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "before and after are mutually exclusive");
        }

        boolean older = before != null;
        ChatCursor.Decoded cursor = before != null || after != null
                ? resolveCursor(conversationId, before != null ? before : after)
                : null;

        // Fetch one extra row so hasMore is authoritative without loading the
        // entire conversation into memory.
        var window = PageRequest.of(0, limit + 1);
        List<Message> page = cursor == null
                ? messages.findLatestPage(conversationId, false, Instant.EPOCH, new UUID(0L, 0L), window)
                : older
                        ? messages.findLatestPage(
                                conversationId, true, cursor.sentAt(), cursor.messageId(), window)
                        : messages.findAfterPage(
                                conversationId, cursor.sentAt(), cursor.messageId(), window);

        boolean hasMore = page.size() > limit;
        List<Message> visible = hasMore ? page.subList(0, limit) : page;
        String nextCursor = null;
        if (hasMore && !visible.isEmpty()) {
            // Both directions consume newest-first windows. Continuing older uses
            // the oldest visible row; continuing newer must also use the oldest
            // visible row so no intervening messages are skipped.
            Message boundary = visible.get(visible.size() - 1);
            nextCursor = ChatCursor.encode(conversationId, boundary);
        }
        return MessagePageResponse.of(
                visible.stream().map(ChatMapper::toMessage).toList(), limit, nextCursor, hasMore);
    }

    private SendResult persist(AuthenticatedAccount account, ChatSendRequest request) {
        Conversation conversation = conversations.requireConversation(account, request.getConversationId());
        if (conversation.getAssignedAgent() == null
                || conversation.getAssignedAgent().getAccountStatus() != AccountStatus.ACTIVE) {
            // Inactive original participants disable protected messaging without
            // transferring, deleting or exposing the conversation to anyone else.
            throw new ApiException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
        }

        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", "send:" + conversation.getId())
                .getResultList();

        Message existing = messages.findByConversationIdAndSenderIdAndIdempotencyKey(
                        conversation.getId(), account.id(), request.getIdempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!request.getContent().equals(existing.getContent())) {
                throw conflict();
            }
            return new SendResult(
                    ChatMapper.toMessage(existing),
                    false,
                    conversation.getId(),
                    account.id(),
                    otherParticipant(conversation, account.id()));
        }

        Instant sentAt = nextSentAt(conversation.getId());
        Message message = new Message();
        message.setConversation(entityManager.getReference(Conversation.class, conversation.getId()));
        message.setSender(entityManager.getReference(User.class, account.id()));
        message.setMessageType("TEXT");
        message.setContent(request.getContent());
        message.setSentAt(sentAt);
        message.setIdempotencyKey(request.getIdempotencyKey());
        messages.saveAndFlush(message);

        audit.recordMessageCreatedInTransaction(
                message.getId(), account.id(), conversation.getId(), message.getIdempotencyKey(), sentAt);
        UUID recipientId = otherParticipant(conversation, account.id());
        notifyOtherParticipant(conversation, recipientId);
        return new SendResult(
                ChatMapper.toMessage(message), true, conversation.getId(), account.id(), recipientId);
    }

    private void notifyOtherParticipant(Conversation conversation, UUID recipientId) {
        Notification notification = new Notification();
        notification.setRecipient(entityManager.getReference(User.class, recipientId));
        notification.setType("NEW_MESSAGE");
        notification.setTitle("New message");
        notification.setBody("You have a new conversation message.");
        notification.setResourceType("CONVERSATION");
        notification.setResourceId(conversation.getId());
        notification.setIsRead(false);
        notifications.save(notification);
    }

    private static UUID otherParticipant(Conversation conversation, UUID senderId) {
        return conversation.getBuyer().getId().equals(senderId)
                ? conversation.getAssignedAgent().getId()
                : conversation.getBuyer().getId();
    }

    private Instant nextSentAt(UUID conversationId) {
        Object value = entityManager.createNativeQuery("""
                        select greatest(
                            clock_timestamp(),
                            coalesce(
                                (select max(sent_at) + interval '1 microsecond'
                                 from messages where conversation_id = :conversationId),
                                clock_timestamp()
                            )
                        )
                        """)
                .setParameter("conversationId", conversationId)
                .getSingleResult();
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unexpected database timestamp type: " + value.getClass());
    }

    private ChatCursor.Decoded resolveCursor(UUID conversationId, String cursor) {
        ChatCursor.Decoded decoded = ChatCursor.decode(cursor, conversationId);
        Message anchor = messages.findByConversationIdAndId(conversationId, decoded.messageId())
                .orElseThrow(ChatMessageService::invalidCursor);
        if (!anchor.getSentAt().equals(decoded.sentAt())) {
            throw invalidCursor();
        }
        return decoded;
    }

    private static void validate(ChatSendRequest request) {
        if (request == null
                || request.getConversationId() == null
                || request.getIdempotencyKey() == null
                || request.getContent() == null
                || request.getContent().isBlank()
                || request.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid chat message payload");
        }
        UnknownFieldGuard.reject(request.unknownFields());
    }

    private static AuthenticatedAccount throwUnauthorized() {
        throw new ApiException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    private static boolean isUniqueViolation(RuntimeException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static ApiException conflict() {
        return new ApiException(
                ErrorCode.CONFLICT, "Idempotency key was already used with different content");
    }

    private static ApiException invalidCursor() {
        return new ApiException(ErrorCode.BAD_REQUEST, "Invalid message cursor");
    }

    private static ApiException denied() {
        return new ApiException(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }
}
