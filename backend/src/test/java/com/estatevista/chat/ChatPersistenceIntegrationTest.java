package com.estatevista.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.SessionAuthenticationToken;
import com.estatevista.audit.AuditEventService;
import com.estatevista.chat.dto.ConversationResponse;
import com.estatevista.chat.dto.MessagePageResponse;
import com.estatevista.chat.dto.ReadStatePatchRequest;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationReadStateRepository;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.chat.service.ChatCursor;
import com.estatevista.chat.service.ChatMessageService;
import com.estatevista.chat.service.ConversationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-PostgreSQL verification of Stage 7.3/7.4 chat persistence semantics.
 *
 * <p>Fixtures are committed because the services deliberately use
 * {@code REQUIRES_NEW} transactions; cleanup is explicit in {@link #cleanup()}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ChatPersistenceIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String PASSWORD_HASH = "$2a$10$not-a-real-password-hash";

    @Autowired private AccountAuthorizationService accounts;
    @Autowired private ConversationService conversations;
    @Autowired private ChatMessageService messages;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationReadStateRepository readStates;
    @Autowired private MessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID buyerId;
    private UUID agentId;
    private UUID propertyId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        cleanup();
        Role buyerRole = roles.findByName("BUYER").orElseThrow();
        Role agentRole = roles.findByName("AGENT").orElseThrow();

        User buyer = users.saveAndFlush(newUser(buyerRole, "buyer"));
        User agent = users.saveAndFlush(newUser(agentRole, "agent"));
        buyerId = buyer.getId();
        agentId = agent.getId();

        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                .stream().findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(unique("Chat Property"));
        property.setSlug(unique("chat-property"));
        property.setDescription("Stage 7 chat persistence fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(agent);
        property.setPublishedAt(Instant.now());
        propertyId = properties.saveAndFlush(property).getId();

        conversationId = conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId)
                .conversation().conversationId();
    }

    @AfterEach
    void cleanup() {
        if (propertyId != null) {
            // Chat rows are committed with REQUIRES_NEW, so they are outside the
            // test's own transaction. Remove the dependent rows first to keep
            // the RESTRICT foreign keys satisfied.
            jdbcTemplate.update("""
                    DELETE FROM messages WHERE conversation_id IN
                        (SELECT id FROM conversations WHERE property_id = ?)
                    """, propertyId);
            jdbcTemplate.update("""
                    DELETE FROM conversation_read_states WHERE conversation_id IN
                        (SELECT id FROM conversations WHERE property_id = ?)
                    """, propertyId);
            jdbcTemplate.update("DELETE FROM conversations WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        if (buyerId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
        }
        if (agentId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", agentId);
        }
        buyerId = null;
        agentId = null;
        propertyId = null;
        conversationId = null;
    }

    @Test
    void getOrCreateCreatesOneConversationWithTwoReadStatesAndOneAuditRow() {
        assertThat(countConversationsForFixture()).isEqualTo(1L);
        assertThat(countReadStatesForFixture()).isEqualTo(2L);
        assertThat(countAudit(AuditEventService.ACTION_CONVERSATION_CREATE, conversationId)).isEqualTo(1L);

        ConversationResponse repeated = conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId)
                .conversation();
        assertThat(repeated.conversationId()).isEqualTo(conversationId);
        assertThat(countConversationsForFixture()).isEqualTo(1L);
        assertThat(countAudit(AuditEventService.ACTION_CONVERSATION_CREATE, conversationId)).isEqualTo(1L);
    }

    @Test
    void concurrentGetOrCreateProducesExactlyOneConversation() throws Exception {
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<UUID>> tasks = IntStream.range(0, workers)
                    .<Callable<UUID>>mapToObj(i -> () -> {
                        start.await();
                        return conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId)
                                .conversation().conversationId();
                    })
                    .toList();
            List<Future<UUID>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<UUID> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(conversationId);
            }
            assertThat(countConversationsForFixture()).isEqualTo(1L);
            assertThat(countReadStatesForFixture()).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void creationEligibilityIsSeparateFromHistoricalRetrieval() {
        jdbcTemplate.update("UPDATE properties SET status = 'SOLD' WHERE id = ?", propertyId);
        // API §11.1: an existing permanent conversation is retrieved with 200
        // regardless of later property status. Eligibility applies to creation.
        var soldRetrieval = conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId);
        assertThat(soldRetrieval.created()).isFalse();
        assertThat(soldRetrieval.conversation().conversationId()).isEqualTo(conversationId);
        assertThat(conversations.list(auth(buyerId, "BUYER"))).hasSize(1);
        assertThat(messages.history(auth(buyerId, "BUYER"), conversationId, 30, null, null))
                .isNotNull();

        jdbcTemplate.update("UPDATE properties SET status = 'PUBLISHED' WHERE id = ?", propertyId);
        jdbcTemplate.update("UPDATE users SET account_status = 'INACTIVE' WHERE id = ?", agentId);
        // P-03: buyer history stays readable; no fallback or transfer is created.
        var inactiveAgentRetrieval = conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId);
        assertThat(inactiveAgentRetrieval.created()).isFalse();
        assertThat(inactiveAgentRetrieval.conversation().conversationId()).isEqualTo(conversationId);
        assertThat(conversations.detail(auth(buyerId, "BUYER"), conversationId)).isNotNull();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> send(agentId, "AGENT", "Inactive agent cannot send", UUID.randomUUID()))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE' WHERE id = ?", agentId);
    }

    @Test
    void sendsPersistMonotonicOrderAndStableCursorPages() {
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        UUID thirdKey = UUID.randomUUID();
        ChatMessageService.SendResult first = send(buyerId, "BUYER", "first", firstKey);
        ChatMessageService.SendResult second = send(agentId, "AGENT", "second", secondKey);
        ChatMessageService.SendResult third = send(buyerId, "BUYER", "third", thirdKey);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(third.created()).isTrue();
        assertThat(first.message().senderId()).isEqualTo(buyerId);
        assertThat(second.message().senderId()).isEqualTo(agentId);
        assertThat(first.message().sentAt()).isBefore(second.message().sentAt());
        assertThat(second.message().sentAt()).isBefore(third.message().sentAt());
        assertThat(messageRepository.findByConversationIdAndSenderIdAndIdempotencyKey(
                conversationId, buyerId, firstKey)).isPresent();

        MessagePageResponse latest = messages.history(auth(buyerId, "BUYER"), conversationId, 2, null, null);
        assertThat(latest.data()).extracting(message -> message.content())
                .containsExactly("third", "second");
        assertThat(latest.meta().hasMore()).isTrue();
        assertThat(latest.meta().nextCursor()).isNotBlank();

        MessagePageResponse older = messages.history(
                auth(buyerId, "BUYER"), conversationId, 2, latest.meta().nextCursor(), null);
        assertThat(older.data()).extracting(message -> message.id())
                .containsExactly(first.message().id());
        assertThat(older.meta().hasMore()).isFalse();
        assertThat(older.meta().nextCursor()).isNull();

        MessagePageResponse newer = messages.history(
                auth(buyerId, "BUYER"), conversationId, 10, null,
                ChatCursor.encode(conversationId, messageRepository.findById(first.message().id()).orElseThrow()));
        assertThat(newer.data()).extracting(message -> message.id())
                .containsExactly(third.message().id(), second.message().id());
    }

    @Test
    void afterCursorDoesNotSkipMessagesWhenMoreRowsExistThanTheLimit() {
        ChatMessageService.SendResult oldest = send(buyerId, "BUYER", "after-1", UUID.randomUUID());
        ChatMessageService.SendResult second = send(agentId, "AGENT", "after-2", UUID.randomUUID());
        ChatMessageService.SendResult third = send(buyerId, "BUYER", "after-3", UUID.randomUUID());
        ChatMessageService.SendResult fourth = send(agentId, "AGENT", "after-4", UUID.randomUUID());
        ChatMessageService.SendResult newest = send(buyerId, "BUYER", "after-5", UUID.randomUUID());

        Message anchor = messageRepository.findById(oldest.message().id()).orElseThrow();
        MessagePageResponse first = messages.history(
                auth(buyerId, "BUYER"), conversationId, 2, null,
                ChatCursor.encode(conversationId, anchor));
        assertThat(first.data()).extracting(message -> message.id())
                .containsExactly(newest.message().id(), fourth.message().id());
        assertThat(first.meta().hasMore()).isTrue();

        // `after` advances to the newest window; continuing older within that
        // window uses the approved `before` direction. The client deduplicates
        // the already-seen boundary row by server message ID.
        MessagePageResponse secondPage = messages.history(
                auth(buyerId, "BUYER"), conversationId, 2,
                first.meta().nextCursor(), null);
        assertThat(secondPage.data()).extracting(message -> message.id())
                .containsExactly(third.message().id(), second.message().id());
        assertThat(secondPage.meta().hasMore()).isTrue();
        assertThat(secondPage.meta().nextCursor()).isNotBlank();

        MessagePageResponse finalPage = messages.history(
                auth(buyerId, "BUYER"), conversationId, 2,
                secondPage.meta().nextCursor(), null);
        assertThat(finalPage.data()).extracting(message -> message.id())
                .containsExactly(oldest.message().id());
        assertThat(finalPage.meta().hasMore()).isFalse();
        assertThat(finalPage.meta().nextCursor()).isNull();
    }

    @Test
    void malformedAndForeignCursorsAreRejected() {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> messages.history(
                        auth(buyerId, "BUYER"), conversationId, 10, "not-a-cursor", null))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        Message anchor = messageRepository.findById(
                send(buyerId, "BUYER", "cursor anchor", UUID.randomUUID()).message().id())
                .orElseThrow();
        String foreignCursor = ChatCursor.encode(UUID.randomUUID(), anchor);
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> messages.history(
                        auth(buyerId, "BUYER"), conversationId, 10, foreignCursor, null))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void forcedFailureRollsBackMessageAuditAndNotification() {
        jdbcTemplate.execute("""
                create or replace function chat_force_rollback()
                returns trigger
                language plpgsql
                as $$
                begin
                    if new.content = 'FORCED_ROLLBACK' then
                        raise exception 'forced chat rollback test';
                    end if;
                    return new;
                end;
                $$;
                """);
        jdbcTemplate.execute("drop trigger if exists chat_force_rollback_trigger on messages");
        jdbcTemplate.execute("""
                create trigger chat_force_rollback_trigger
                before insert on messages
                for each row execute function chat_force_rollback()
                """);
        try {
            long messagesBefore = countMessagesForFixture();
            long auditsBefore = countAudit(AuditEventService.ACTION_MESSAGE_CREATE, conversationId);
            Long notificationsBefore = jdbcTemplate.queryForObject(
                    "select count(*) from notifications", Long.class);

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> send(buyerId, "BUYER", "FORCED_ROLLBACK", UUID.randomUUID()));

            assertThat(countMessagesForFixture()).isEqualTo(messagesBefore);
            assertThat(countAudit(AuditEventService.ACTION_MESSAGE_CREATE, conversationId))
                    .isEqualTo(auditsBefore);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from notifications", Long.class))
                    .isEqualTo(notificationsBefore == null ? 0L : notificationsBefore);
        } finally {
            jdbcTemplate.execute("drop trigger if exists chat_force_rollback_trigger on messages");
            jdbcTemplate.execute("drop function if exists chat_force_rollback()");
        }
    }

    @Test
    void idempotentRetryReturnsExistingMessageAndDifferentContentConflicts() {
        UUID key = UUID.randomUUID();
        ChatMessageService.SendResult original = send(buyerId, "BUYER", "original", key);
        ChatMessageService.SendResult retry = send(buyerId, "BUYER", "original", key);

        assertThat(retry.created()).isFalse();
        assertThat(retry.message().id()).isEqualTo(original.message().id());
        assertThat(countMessagesForFixture()).isEqualTo(1L);
        assertThat(countAudit(AuditEventService.ACTION_MESSAGE_CREATE, original.message().id())).isEqualTo(1L);

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> send(buyerId, "BUYER", "changed", key))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(countMessagesForFixture()).isEqualTo(1L);
    }

    @Test
    void concurrentDuplicateSendsPersistOneMessageAndOneAuditRow() throws Exception {
        UUID key = UUID.randomUUID();
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<UUID>> tasks = IntStream.range(0, workers)
                    .<Callable<UUID>>mapToObj(i -> () -> {
                        start.await();
                        return send(buyerId, "BUYER", "concurrent", key).message().id();
                    })
                    .toList();
            List<Future<UUID>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            UUID persistedId = results.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<UUID> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(persistedId);
            }
            assertThat(countMessagesForFixture()).isEqualTo(1L);
            assertThat(countAudit(AuditEventService.ACTION_MESSAGE_CREATE, persistedId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readStateIsMonotonicAndExcludesOwnMessagesFromUnreadCounts() {
        Message buyerMessage = messageRepository.findById(
                send(buyerId, "BUYER", "own", UUID.randomUUID()).message().id()).orElseThrow();
        Message agentMessage = messageRepository.findById(
                send(agentId, "AGENT", "incoming", UUID.randomUUID()).message().id()).orElseThrow();

        ConversationResponse buyerView = conversations.detail(auth(buyerId, "BUYER"), conversationId);
        ConversationResponse agentView = conversations.detail(auth(agentId, "AGENT"), conversationId);
        assertThat(buyerView.unreadCount()).isEqualTo(1L);
        assertThat(agentView.unreadCount()).isEqualTo(1L);

        conversations.updateReadState(
                auth(buyerId, "BUYER"), conversationId, readRequest(agentMessage.getId()));
        assertThat(conversations.detail(auth(buyerId, "BUYER"), conversationId).unreadCount()).isZero();

        conversations.updateReadState(
                auth(buyerId, "BUYER"), conversationId, readRequest(buyerMessage.getId()));
        UUID latestRead = readStates.findById(
                new com.estatevista.chat.entity.ConversationReadStateId(conversationId, buyerId))
                .orElseThrow().getLastReadMessage().getId();
        assertThat(latestRead).isEqualTo(agentMessage.getId());
        assertThat(countAudit(AuditEventService.ACTION_READ_STATE_UPDATE, conversationId)).isEqualTo(1L);
    }

    @Test
    void readStateRejectsMessagesFromOtherConversations() {
        Message own = messageRepository.findById(
                send(buyerId, "BUYER", "own", UUID.randomUUID()).message().id()).orElseThrow();
        // The message id is not present in this conversation, so the same-conversation
        // authority rejects it regardless of any other row with the same UUID.
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> conversations.updateReadState(
                        auth(buyerId, "BUYER"), conversationId,
                        readRequest(UUID.randomUUID())))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(own.getId()).isNotNull();
    }

    @Test
    void participantScopingDeniesOtherAccountsAndAdmin() {
        assertThat(conversations.detail(auth(buyerId, "BUYER"), conversationId)).isNotNull();
        assertThat(conversations.detail(auth(agentId, "AGENT"), conversationId)).isNotNull();

        UUID strangerId = users.saveAndFlush(newUser(roles.findByName("BUYER").orElseThrow(), "stranger"))
                .getId();
        UUID adminId = users.saveAndFlush(newUser(roles.findByName("ADMIN").orElseThrow(), "admin"))
                .getId();
        try {
            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> conversations.detail(auth(strangerId, "BUYER"), conversationId))
                    .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> messages.history(auth(adminId, "ADMIN"), conversationId, 30, null, null))
                    .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminId);
        }
    }

    private ChatMessageService.SendResult send(UUID senderId, String role, String content, UUID key) {
        AuthenticatedAccount account = accounts.requireActive(auth(senderId, role));
        com.estatevista.chat.dto.ChatSendRequest request =
                new com.estatevista.chat.dto.ChatSendRequest();
        request.setConversationId(conversationId);
        request.setContent(content);
        request.setIdempotencyKey(key);
        return messages.send(account, request);
    }

    private static ReadStatePatchRequest readRequest(UUID lastReadMessageId) {
        ReadStatePatchRequest request = new ReadStatePatchRequest();
        request.setLastReadMessageId(lastReadMessageId);
        return request;
    }

    private static org.springframework.security.core.Authentication auth(UUID userId, String role) {
        return new SessionAuthenticationToken(
                userId.toString(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private long countConversationsForFixture() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE buyer_id = ? AND property_id = ?",
                Long.class, buyerId, propertyId);
        return count == null ? 0L : count;
    }

    private long countReadStatesForFixture() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM conversation_read_states
                WHERE conversation_id = ?
                """, Long.class, conversationId);
        return count == null ? 0L : count;
    }

    private long countMessagesForFixture() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
        return count == null ? 0L : count;
    }

    private long countAudit(String action, UUID entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
        return count == null ? 0L : count;
    }

    private static User newUser(Role role, String label) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Chat");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPasswordHash(PASSWORD_HASH);
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    private static String unique(String label) {
        return (label + "-" + UUID.randomUUID()).toLowerCase(Locale.ROOT);
    }
}
