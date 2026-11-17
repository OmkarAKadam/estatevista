package com.estatevista.authorization;

import java.util.UUID;

import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationReadStateRepository;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.favorite.entity.Favorite;
import com.estatevista.favorite.repository.FavoriteRepository;
import com.estatevista.inquiry.entity.Inquiry;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.notification.entity.Notification;
import com.estatevista.notification.repository.NotificationRepository;
import com.estatevista.property.entity.Property;
import com.estatevista.property.repository.PropertyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable ownership and assignment policies for Stage 3.5 resources.
 *
 * <p>Role membership is necessary but never sufficient. Every private resource
 * is resolved by its server-side identifier and checked against the trusted
 * account. Missing resources and forbidden resources deliberately produce the
 * same generic {@code FORBIDDEN} error so an attacker cannot enumerate another
 * user's data by changing an ID.</p>
 *
 * <p>There are no REST controllers for these resources yet; the policies are
 * exercised by service integration tests and by the STOMP authorization
 * interceptor. Future controllers must call these methods rather than
 * reimplementing ownership checks.</p>
 */
@Service
@Transactional(readOnly = true)
public class ResourceAuthorizationService {

    private final AccountAuthorizationService accounts;
    private final FavoriteRepository favoriteRepository;
    private final InquiryRepository inquiryRepository;
    private final PropertyRepository propertyRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReadStateRepository readStateRepository;
    private final NotificationRepository notificationRepository;

    public ResourceAuthorizationService(
            AccountAuthorizationService accounts,
            FavoriteRepository favoriteRepository,
            InquiryRepository inquiryRepository,
            PropertyRepository propertyRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConversationReadStateRepository readStateRepository,
            NotificationRepository notificationRepository) {
        this.accounts = accounts;
        this.favoriteRepository = favoriteRepository;
        this.inquiryRepository = inquiryRepository;
        this.propertyRepository = propertyRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.readStateRepository = readStateRepository;
        this.notificationRepository = notificationRepository;
    }

    /** The account may only act on its own user record. */
    public void requireSelf(AuthenticatedAccount account, UUID userId) {
        if (account == null || userId == null || !account.id().equals(userId)) {
            throw denied();
        }
    }

    public void requireBuyer(AuthenticatedAccount account) {
        accounts.requireRole(account, PlatformRole.BUYER);
    }

    public void requireAgent(AuthenticatedAccount account) {
        accounts.requireRole(account, PlatformRole.AGENT);
    }

    public void requireAdmin(AuthenticatedAccount account) {
        accounts.requireRole(account, PlatformRole.ADMIN);
    }

    /** A favorite is private to the buyer who created it. */
    public void requireFavoriteOwner(AuthenticatedAccount account, UUID favoriteId) {
        requireBuyer(account);
        Favorite favorite = favoriteId == null
                ? null
                : favoriteRepository.findById(favoriteId).orElse(null);
        if (favorite == null || favorite.getBuyer() == null) {
            throw denied();
        }
        requireSelf(account, favorite.getBuyer().getId());
    }

    /**
     * An inquiry is available to its submitting buyer, its originally assigned
     * agent, and ADMIN monitoring. ADMIN does not gain conversation access.
     */
    public void requireInquiryAccess(AuthenticatedAccount account, UUID inquiryId) {
        Inquiry inquiry = inquiryId == null
                ? null
                : inquiryRepository.findById(inquiryId).orElse(null);
        if (inquiry == null) {
            throw denied();
        }
        if (account == null) {
            throw denied();
        }
        switch (account.role()) {
            case BUYER -> requireSelf(account, ownerId(inquiry.getBuyer()));
            case AGENT -> requireSelf(account, ownerId(inquiry.getAssignedAgent()));
            case ADMIN -> { /* documented platform monitoring capability */ }
        }
    }

    /** An agent may only manage listings whose primary agent they are. */
    public void requirePropertyAssignment(AuthenticatedAccount account, UUID propertyId) {
        requireAgent(account);
        requireAssignedProperty(account, propertyId);
    }

    /** ADMIN may manage any listing; AGENT only an assigned listing. */
    public void requirePropertyManagement(AuthenticatedAccount account, UUID propertyId) {
        if (account == null) {
            throw denied();
        }
        switch (account.role()) {
            case ADMIN -> { /* approved platform management capability */ }
            case AGENT -> requireAssignedProperty(account, propertyId);
            case BUYER -> throw denied();
        }
    }

    /**
     * Conversation participants are exactly the buyer and the originally
     * assigned agent. ADMIN has no automatic access to private conversations.
     */
    public void requireConversationAccess(AuthenticatedAccount account, UUID conversationId) {
        if (account == null || conversationId == null) {
            throw denied();
        }
        var conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            throw denied();
        }
        switch (account.role()) {
            case BUYER -> requireSelf(account, ownerId(conversation.getBuyer()));
            case AGENT -> requireSelf(account, ownerId(conversation.getAssignedAgent()));
            case ADMIN -> throw denied();
        }
    }

    /** A message is visible only inside a conversation the account participates in. */
    public void requireMessageAccess(AuthenticatedAccount account, UUID messageId) {
        Message message = messageId == null
                ? null
                : messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getConversation() == null) {
            throw denied();
        }
        requireConversationAccess(account, message.getConversation().getId());
    }

    /** Sender identity for a message operation must match the trusted account. */
    public void requireMessageSender(AuthenticatedAccount account, UUID messageId) {
        Message message = messageId == null
                ? null
                : messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getSender() == null) {
            throw denied();
        }
        requireMessageAccess(account, messageId);
        requireSelf(account, message.getSender().getId());
    }

    /** Read state belongs to one participant and one conversation. */
    public void requireReadStateOwner(AuthenticatedAccount account, ConversationReadStateId id) {
        if (id == null) {
            throw denied();
        }
        requireSelf(account, id.getUserId());
        requireConversationAccess(account, id.getConversationId());

        ConversationReadState state = readStateRepository.findById(id).orElse(null);
        if (state == null) {
            throw denied();
        }
        if (state.getLastReadMessage() != null
                && state.getLastReadMessage().getConversation() != null
                && !id.getConversationId().equals(state.getLastReadMessage().getConversation().getId())) {
            throw denied();
        }
    }

    /** Notifications are private to their recipient. */
    public void requireNotificationOwner(AuthenticatedAccount account, UUID notificationId) {
        Notification notification = notificationId == null
                ? null
                : notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getRecipient() == null) {
            throw denied();
        }
        requireSelf(account, notification.getRecipient().getId());
    }

    private void requireAssignedProperty(AuthenticatedAccount account, UUID propertyId) {
        Property property = propertyId == null
                ? null
                : propertyRepository.findById(propertyId).orElse(null);
        if (property == null || property.getPrimaryAgent() == null) {
            throw denied();
        }
        requireSelf(account, property.getPrimaryAgent().getId());
    }

    private static UUID ownerId(com.estatevista.user.entity.User user) {
        return user == null ? null : user.getId();
    }

    private static ApiException denied() {
        return new ApiException(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }
}
