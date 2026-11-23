package com.estatevista.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.estatevista.chat.entity.Conversation;
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
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL verification of ownership and assignment policies.
 *
 * <p>All assertions use records created in a rolled-back transaction, so the
 * Flyway-seeded reference data is unchanged. Every forbidden path returns the
 * same generic error whether the resource is missing or owned by someone else.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class ResourceAuthorizationIntegrationTest {

    @Autowired
    private AccountAuthorizationService accounts;

    @Autowired
    private ResourceAuthorizationService resources;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PropertySubtypeRepository propertySubtypeRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationReadStateRepository conversationReadStateRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    private User buyer;
    private User otherBuyer;
    private User assignedAgent;
    private User otherAgent;
    private User admin;
    private AuthenticatedAccount buyerAccount;
    private AuthenticatedAccount otherBuyerAccount;
    private AuthenticatedAccount assignedAgentAccount;
    private AuthenticatedAccount otherAgentAccount;
    private AuthenticatedAccount adminAccount;
    private Property property;
    private Favorite favorite;
    private Inquiry inquiry;
    private Conversation conversation;
    private Message message;
    private ConversationReadStateId readStateId;
    private Notification notification;

    @BeforeEach
    void setUp() {
        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        Role agentRole = roleRepository.findByName("AGENT").orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();

        buyer = newUser(buyerRole, "rb-buyer");
        otherBuyer = newUser(buyerRole, "rb-other-buyer");
        assignedAgent = newUser(agentRole, "rb-agent");
        otherAgent = newUser(agentRole, "rb-other-agent");
        admin = newUser(adminRole, "rb-admin");
        userRepository.saveAllAndFlush(List.of(buyer, otherBuyer, assignedAgent, otherAgent, admin));

        PropertyCategory residential = propertyCategoryRepository.findAll().stream()
                .filter(category -> category.getName().equals("RESIDENTIAL"))
                .findFirst()
                .orElseThrow();
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("Apartment " + shortId());
        propertySubtypeRepository.saveAndFlush(subtype);

        property = newProperty(residential, subtype, assignedAgent, admin);
        propertyRepository.saveAndFlush(property);

        favorite = new Favorite();
        favorite.setBuyer(buyer);
        favorite.setProperty(property);
        favoriteRepository.saveAndFlush(favorite);

        inquiry = new Inquiry();
        inquiry.setBuyer(buyer);
        inquiry.setProperty(property);
        inquiry.setAssignedAgent(assignedAgent);
        inquiry.setMessage("Is this available?");
        inquiry.setStatus("NEW");
        inquiryRepository.saveAndFlush(inquiry);

        conversation = new Conversation();
        conversation.setBuyer(buyer);
        conversation.setProperty(property);
        conversation.setAssignedAgent(assignedAgent);
        conversation.setStatus("OPEN");
        conversationRepository.saveAndFlush(conversation);

        message = new Message();
        message.setConversation(conversation);
        message.setSender(buyer);
        message.setMessageType("TEXT");
        message.setContent("Hello");
        messageRepository.saveAndFlush(message);

        readStateId = new ConversationReadStateId(conversation.getId(), buyer.getId());
        ConversationReadState readState = new ConversationReadState();
        readState.setId(readStateId);
        readState.setConversation(conversation);
        readState.setUser(buyer);
        readState.setLastReadMessage(message);
        readState.setLastReadAt(Instant.now());
        conversationReadStateRepository.saveAndFlush(readState);

        notification = new Notification();
        notification.setRecipient(assignedAgent);
        notification.setType("NEW_INQUIRY");
        notification.setTitle("New inquiry");
        notification.setBody("A buyer sent an inquiry.");
        notification.setResourceType("INQUIRY");
        notification.setResourceId(inquiry.getId());
        notification.setIsRead(false);
        notificationRepository.saveAndFlush(notification);

        buyerAccount = accountFor(buyer, PlatformRole.BUYER);
        otherBuyerAccount = accountFor(otherBuyer, PlatformRole.BUYER);
        assignedAgentAccount = accountFor(assignedAgent, PlatformRole.AGENT);
        otherAgentAccount = accountFor(otherAgent, PlatformRole.AGENT);
        adminAccount = accountFor(admin, PlatformRole.ADMIN);
    }

    @Test
    void favoritesArePrivateToTheBuyerWhoCreatedThem() {
        resources.requireFavoriteOwner(buyerAccount, favorite.getId());

        assertDenied(() -> resources.requireFavoriteOwner(otherBuyerAccount, favorite.getId()));
        assertDenied(() -> resources.requireFavoriteOwner(assignedAgentAccount, favorite.getId()));
        assertDenied(() -> resources.requireFavoriteOwner(adminAccount, favorite.getId()));
        assertDenied(() -> resources.requireFavoriteOwner(buyerAccount, UUID.randomUUID()));
        assertDenied(() -> resources.requireFavoriteOwner(buyerAccount, null));
    }

    @Test
    void propertyManagementIsLimitedToTheAssignedAgentOrAdmin() {
        resources.requirePropertyAssignment(assignedAgentAccount, property.getId());
        resources.requirePropertyManagement(assignedAgentAccount, property.getId());
        resources.requirePropertyManagement(adminAccount, property.getId());

        assertDenied(() -> resources.requirePropertyAssignment(otherAgentAccount, property.getId()));
        assertDenied(() -> resources.requirePropertyAssignment(buyerAccount, property.getId()));
        assertDenied(() -> resources.requirePropertyAssignment(adminAccount, property.getId()));
        assertDenied(() -> resources.requirePropertyManagement(otherAgentAccount, property.getId()));
        assertDenied(() -> resources.requirePropertyManagement(buyerAccount, property.getId()));
        assertDenied(() -> resources.requirePropertyManagement(assignedAgentAccount, UUID.randomUUID()));
    }

    @Test
    void inquiriesAreVisibleToTheirBuyerTheirAssignedAgentAndAdmin() {
        resources.requireInquiryAccess(buyerAccount, inquiry.getId());
        resources.requireInquiryAccess(assignedAgentAccount, inquiry.getId());
        resources.requireInquiryAccess(adminAccount, inquiry.getId());

        assertDenied(() -> resources.requireInquiryAccess(otherBuyerAccount, inquiry.getId()));
        assertDenied(() -> resources.requireInquiryAccess(otherAgentAccount, inquiry.getId()));
        assertDenied(() -> resources.requireInquiryAccess(buyerAccount, UUID.randomUUID()));
    }

    @Test
    void conversationsAndMessagesArePrivateToTheTwoParticipants() {
        resources.requireConversationAccess(buyerAccount, conversation.getId());
        resources.requireConversationAccess(assignedAgentAccount, conversation.getId());
        resources.requireMessageAccess(buyerAccount, message.getId());
        resources.requireMessageAccess(assignedAgentAccount, message.getId());
        resources.requireMessageSender(buyerAccount, message.getId());

        assertDenied(() -> resources.requireConversationAccess(otherBuyerAccount, conversation.getId()));
        assertDenied(() -> resources.requireConversationAccess(otherAgentAccount, conversation.getId()));
        assertDenied(() -> resources.requireConversationAccess(adminAccount, conversation.getId()));
        assertDenied(() -> resources.requireMessageAccess(otherBuyerAccount, message.getId()));
        assertDenied(() -> resources.requireMessageAccess(adminAccount, message.getId()));
        assertDenied(() -> resources.requireMessageSender(assignedAgentAccount, message.getId()));
        assertDenied(() -> resources.requireMessageSender(otherBuyerAccount, message.getId()));
    }

    @Test
    void readStatesAndNotificationsRemainPrivateToTheirOwner() {
        resources.requireReadStateOwner(buyerAccount, readStateId);
        resources.requireNotificationOwner(assignedAgentAccount, notification.getId());

        assertDenied(() -> resources.requireReadStateOwner(otherBuyerAccount, readStateId));
        assertDenied(() -> resources.requireReadStateOwner(assignedAgentAccount, readStateId));
        assertDenied(() -> resources.requireNotificationOwner(buyerAccount, notification.getId()));
        assertDenied(() -> resources.requireNotificationOwner(adminAccount, notification.getId()));
        assertDenied(() -> resources.requireNotificationOwner(assignedAgentAccount, UUID.randomUUID()));
    }

    @Test
    void conversationAccessUsesOriginalAssignmentNotTheCurrentPropertyAgent() {
        property.setPrimaryAgent(otherAgent);
        propertyRepository.saveAndFlush(property);
        entityManager.flush();
        entityManager.clear();

        resources.requireConversationAccess(buyerAccount, conversation.getId());
        resources.requireConversationAccess(assignedAgentAccount, conversation.getId());

        assertDenied(() -> resources.requireConversationAccess(otherAgentAccount, conversation.getId()));
        assertDenied(() -> resources.requireConversationAccess(adminAccount, conversation.getId()));
    }

    @Test
    void missingResourcesAndClientSuppliedOwnershipIdsFailClosedWithTheSameError() {
        UUID spoofedUserId = otherBuyer.getId();
        AuthenticatedAccount spoofed = AuthenticatedAccount.of(
                spoofedUserId, PlatformRole.AGENT, AccountStatus.ACTIVE);

        assertDenied(() -> resources.requireSelf(buyerAccount, spoofedUserId));
        assertDenied(() -> resources.requireFavoriteOwner(spoofed, favorite.getId()));
        assertDenied(() -> resources.requireConversationAccess(spoofed, conversation.getId()));
        assertDenied(() -> resources.requireMessageSender(spoofed, message.getId()));
        assertDenied(() -> resources.requireReadStateOwner(
                buyerAccount, new ConversationReadStateId(conversation.getId(), spoofedUserId)));
        assertDenied(() -> resources.requireInquiryAccess(null, inquiry.getId()));
        assertThat(ErrorCode.FORBIDDEN.defaultMessage()).isNotBlank();
    }

    private AuthenticatedAccount accountFor(User user, PlatformRole expectedRole) {
        AuthenticatedAccount account = accounts.requireActive(
                new SessionAuthenticationToken(user.getId().toString(), List.of()));
        assertThat(account.role()).isEqualTo(expectedRole);
        assertThat(account.id()).isEqualTo(user.getId());
        return account;
    }

    private static void assertDenied(Executable executable) {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(executable::execute)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .withMessage(ErrorCode.FORBIDDEN.defaultMessage());
    }

    private static User newUser(Role role, String label) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Owner");
        user.setLastName("Test");
        user.setEmail(label + "-" + shortId() + "@example.com");
        user.setPasswordHash("$2a$10$not-a-real-password-hash");
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    private static Property newProperty(
            PropertyCategory category, PropertySubtype subtype, User agent, User admin) {
        Property property = new Property();
        property.setTitle("Authorization property " + shortId());
        property.setSlug("authorization-property-" + shortId());
        property.setDescription("Ownership policy verification property.");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("250000.00"));
        property.setCurrencyCode("USD");
        property.setAreaValue(new BigDecimal("1250.50"));
        property.setAreaUnit("SQFT");
        property.setCity("Bengaluru");
        property.setState("Karnataka");
        property.setCountry("India");
        property.setLatitude(new BigDecimal("12.9715990"));
        property.setLongitude(new BigDecimal("77.5945660"));
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setUpdatedBy(admin);
        property.setPublishedAt(Instant.now());
        return property;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
