package com.estatevista.chat.service;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.chat.dto.ConversationResponse;
import com.estatevista.chat.dto.CreatedConversation;
import com.estatevista.chat.dto.ReadStatePatchRequest;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationReadStateRepository;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conversation creation, participation-scoped reads and monotonic read state.
 *
 * <p>New conversations require an eligible published property and an active
 * current agent. Existing history remains readable for its original
 * participants regardless of later property status or reassignment. No
 * conversation status mutation or participant transfer exists here.</p>
 */
@Service
@Transactional(readOnly = true)
public class ConversationService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService authorization;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationReadStateRepository readStates;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AuditEventService audit;
    private final EntityManager entityManager;
    private final TransactionTemplate creationTemplate;

    public ConversationService(
            AccountAuthorizationService accounts,
            ResourceAuthorizationService authorization,
            ConversationRepository conversations,
            MessageRepository messages,
            ConversationReadStateRepository readStates,
            PropertyRepository properties,
            UserRepository users,
            AuditEventService audit,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.authorization = authorization;
        this.conversations = conversations;
        this.messages = messages;
        this.readStates = readStates;
        this.properties = properties;
        this.users = users;
        this.audit = audit;
        this.entityManager = entityManager;
        this.creationTemplate = new TransactionTemplate(transactionManager);
        this.creationTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Returns the permanent conversation for a buyer/property pair, creating it
     * only when the approved eligibility rule holds. The unique constraint is
     * the concurrency authority; a lost race re-reads and returns the winner
     * with {@code created = false}.
     */
    public CreatedConversation getOrCreate(Authentication authentication, UUID propertyId) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        accounts.requireRole(account, PlatformRole.BUYER);
        if (propertyId == null) {
            throw notFound();
        }

        Conversation existing = conversations.findByBuyerIdAndPropertyId(account.id(), propertyId)
                .orElse(null);
        if (existing != null) {
            return new CreatedConversation(response(existing, account.id()), false);
        }

        try {
            return creationTemplate.execute(status -> createEligibleConversation(account.id(), propertyId));
        } catch (RuntimeException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            Conversation winner = conversations.findByBuyerIdAndPropertyId(account.id(), propertyId)
                    .orElseThrow(ConversationService::notFound);
            return new CreatedConversation(response(winner, account.id()), false);
        }
    }

    public List<ConversationResponse> list(Authentication authentication) {
        AuthenticatedAccount account = requireParticipantAccount(authentication);
        List<Conversation> owned = account.role() == PlatformRole.BUYER
                ? conversations.findAllByBuyerIdOrderByCreatedAtDescIdDesc(account.id())
                : conversations.findAllByAssignedAgentIdOrderByCreatedAtDescIdDesc(account.id());
        return owned.stream().map(conversation -> response(conversation, account.id())).toList();
    }

    public ConversationResponse detail(Authentication authentication, UUID conversationId) {
        AuthenticatedAccount account = requireParticipantAccount(authentication);
        Conversation conversation = requireConversation(account, conversationId);
        return response(conversation, account.id());
    }

    /**
     * Advances one participant's own read position. The advisory transaction
     * lock serializes competing updates and the highest valid viewed position
     * wins. A lower or repeated position is accepted idempotently.
     */
    @Transactional
    public ConversationResponse updateReadState(
            Authentication authentication,
            UUID conversationId,
            ReadStatePatchRequest request) {
        AuthenticatedAccount account = requireParticipantAccount(authentication);
        Conversation conversation = requireConversation(account, conversationId);
        if (request == null || request.getLastReadMessageId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "lastReadMessageId is required");
        }

        lockConversation("read-state", conversationId);
        Message target = messages.findByConversationIdAndId(conversationId, request.getLastReadMessageId())
                .orElseThrow(ConversationService::notFound);

        ConversationReadStateId id = new ConversationReadStateId(conversationId, account.id());
        authorization.requireReadStateOwner(account, id);
        ConversationReadState state = readStates.findById(id).orElse(null);
        if (state == null) {
            state = new ConversationReadState();
            state.setId(id);
            state.setConversation(conversation);
            state.setUser(referenceUser(account.id()));
            state.setLastReadMessage(target);
            state.setLastReadAt(Instant.now());
            readStates.saveAndFlush(state);
            audit.recordReadStateUpdatedInTransaction(
                    conversationId, account.id(), target.getId(), state.getLastReadAt());
        } else if (isNewer(target, state.getLastReadMessage())) {
            state.setLastReadMessage(target);
            state.setLastReadAt(Instant.now());
            readStates.saveAndFlush(state);
            audit.recordReadStateUpdatedInTransaction(
                    conversationId, account.id(), target.getId(), state.getLastReadAt());
        }
        return response(conversation, account.id());
    }

    private CreatedConversation createEligibleConversation(UUID buyerId, UUID propertyId) {
        Property property = properties.findById(propertyId).orElseThrow(ConversationService::notFound);
        if (property.getStatus() != PropertyStatus.PUBLISHED
                || property.getPrimaryAgent() == null) {
            throw notFound();
        }
        User agent = users.findById(property.getPrimaryAgent().getId())
                .orElseThrow(ConversationService::notFound);
        if (agent.getAccountStatus() != AccountStatus.ACTIVE
                || !PlatformRole.AGENT.name().equals(agent.getRole().getName())) {
            throw notFound();
        }

        // Stable UUID order avoids deadlocks between simultaneous creations that
        // involve the same buyer and agent rows.
        List<UUID> userIds = new ArrayList<>(List.of(buyerId, agent.getId()));
        userIds.sort(UUID::compareTo);
        entityManager.createNativeQuery(
                        "select id from users where id in (:ids) order by id for update")
                .setParameter("ids", userIds)
                .getResultList();
        entityManager.createNativeQuery("select id from properties where id = :id for update")
                .setParameter("id", propertyId)
                .getResultList();

        Conversation raced = conversations.findByBuyerIdAndPropertyId(buyerId, propertyId).orElse(null);
        if (raced != null) {
            return new CreatedConversation(response(raced, buyerId), false);
        }

        Conversation conversation = new Conversation();
        conversation.setBuyer(referenceUser(buyerId));
        conversation.setProperty(entityManager.getReference(Property.class, propertyId));
        conversation.setAssignedAgent(referenceUser(agent.getId()));
        conversation.setStatus(ACTIVE_STATUS);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversations.saveAndFlush(conversation);

        initializeReadState(conversation.getId(), buyerId);
        initializeReadState(conversation.getId(), agent.getId());
        audit.recordConversationCreatedInTransaction(
                conversation.getId(), buyerId, propertyId, agent.getId());
        return new CreatedConversation(response(conversation, buyerId), true);
    }

    private void initializeReadState(UUID conversationId, UUID userId) {
        ConversationReadState state = new ConversationReadState();
        state.setId(new ConversationReadStateId(conversationId, userId));
        state.setConversation(entityManager.getReference(Conversation.class, conversationId));
        state.setUser(referenceUser(userId));
        readStates.save(state);
    }

    private ConversationResponse response(Conversation conversation, UUID viewerId) {
        ConversationReadState state = readStates.findByConversationIdAndUserId(
                conversation.getId(), viewerId).orElse(null);
        Message lastMessage = messages
                .findFirstByConversationIdAndDeletedAtIsNullOrderBySentAtDescIdDesc(conversation.getId())
                .orElse(null);
        long unread = state == null || state.getLastReadMessage() == null
                ? messages.countUnread(conversation.getId(), viewerId)
                : messages.countUnreadAfter(
                        conversation.getId(),
                        viewerId,
                        state.getLastReadMessage().getSentAt(),
                        state.getLastReadMessage().getId());
        return ChatMapper.toConversation(conversation, unread, java.util.Optional.ofNullable(lastMessage));
    }

    private User referenceUser(UUID userId) {
        return userId == null ? null : entityManager.getReference(User.class, userId);
    }

    private AuthenticatedAccount requireParticipantAccount(Authentication authentication) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        if (account.role() != PlatformRole.BUYER && account.role() != PlatformRole.AGENT) {
            throw denied();
        }
        return account;
    }

    /** Shared participant predicate used by the REST and STOMP surfaces. */
    public Conversation requireConversation(AuthenticatedAccount account, UUID conversationId) {
        if (conversationId == null) {
            throw notFound();
        }
        return conversations.findParticipantConversation(
                        conversationId, account.id(), account.role() == PlatformRole.BUYER)
                .orElseThrow(ConversationService::notFound);
    }

    private void lockConversation(String scope, UUID conversationId) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", scope + ":" + conversationId)
                .getResultList();
    }

    private static boolean isNewer(Message candidate, Message current) {
        if (current == null) {
            return true;
        }
        int sentAt = candidate.getSentAt().compareTo(current.getSentAt());
        return sentAt > 0 || (sentAt == 0 && candidate.getId().compareTo(current.getId()) > 0);
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

    private static ApiException denied() {
        return new ApiException(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }

    /** Non-existent, ineligible and non-participant resources are indistinguishable. */
    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }
}
