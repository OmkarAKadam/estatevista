package com.estatevista.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.SessionAuthenticationToken;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationReadStateRepository;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.security.RateLimitService;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL REST acceptance tests for the Stage 7.3 conversation surface.
 *
 * <p>Fixtures are committed in {@link BeforeTransaction} and removed in
 * {@link AfterTransaction}: {@code ConversationService} and
 * {@code AuditEventService} deliberately use {@code REQUIRES_NEW}, so they
 * cannot observe uncommitted rows from the test-managed transaction. Business
 * mutations made inside each test still roll back with that transaction.</p>
 *
 * <p>HTTP calls use real repositories for fixtures, {@code MockMvc} for the
 * filter chain, and a {@code SessionAuthenticationToken} whose principal is the
 * user UUID string, which is the only identity {@code AccountAuthorizationService}
 * trusts. The rate-limit test only lowers the existing {@code authenticated_api}
 * bucket's configured threshold; it does not introduce a chat-specific limit.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "app.rate-limit.authenticated-api.max-attempts=2",
        "app.rate-limit.authenticated-api.window-seconds=60"
})
@Transactional
class ChatRestIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PREFIX = "s73-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

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
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationReadStateRepository conversationReadStateRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();

    private User buyer;
    private User otherBuyer;
    private User primaryAgent;
    private User otherAgent;
    private User inactiveAgent;
    private User admin;
    private PropertyCategory category;
    private PropertySubtype subtype;
    private Property publishedProperty;
    private Property draftProperty;
    private Property inactiveAgentProperty;
    private Property conversationProperty;
    private Property reassignmentProperty;
    private Property statusProperty;
    private Conversation conversation;
    private Conversation reassignmentConversation;
    private Conversation statusConversation;
    private Message olderAgentMessage;
    private Message newerAgentMessage;
    private Message buyerMessage;

    @BeforeTransaction
    void seedDatabase() {
        buyer = persistUser("BUYER", "buyer", AccountStatus.ACTIVE);
        otherBuyer = persistUser("BUYER", "other-buyer", AccountStatus.ACTIVE);
        primaryAgent = persistUser("AGENT", "agent", AccountStatus.ACTIVE);
        otherAgent = persistUser("AGENT", "other-agent", AccountStatus.ACTIVE);
        inactiveAgent = persistUser("AGENT", "inactive-agent", AccountStatus.INACTIVE);
        admin = persistUser("ADMIN", "admin", AccountStatus.ACTIVE);

        category = propertyCategoryRepository.findAll().stream()
                .filter(candidate -> "RESIDENTIAL".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        subtype = new PropertySubtype();
        subtype.setCategory(category);
        subtype.setName(unique("Chat Flat"));
        propertySubtypeRepository.saveAndFlush(subtype);

        publishedProperty = persistProperty("creation", PropertyStatus.PUBLISHED, primaryAgent);
        draftProperty = persistProperty("draft", PropertyStatus.DRAFT, primaryAgent);
        inactiveAgentProperty = persistProperty("inactive", PropertyStatus.PUBLISHED, inactiveAgent);
        conversationProperty = persistProperty("conversation", PropertyStatus.PUBLISHED, primaryAgent);
        reassignmentProperty = persistProperty("reassignment", PropertyStatus.PUBLISHED, primaryAgent);
        statusProperty = persistProperty("status", PropertyStatus.PUBLISHED, primaryAgent);

        conversation = persistConversation(buyer, primaryAgent, conversationProperty);
        reassignmentConversation = persistConversation(buyer, primaryAgent, reassignmentProperty);
        statusConversation = persistConversation(buyer, primaryAgent, statusProperty);

        Instant base = Instant.now().minusSeconds(30);
        olderAgentMessage = persistMessage(conversation, primaryAgent, "Older agent message",
                base, UUID.randomUUID());
        newerAgentMessage = persistMessage(conversation, primaryAgent, "Newer agent message",
                base.plusSeconds(10), UUID.randomUUID());
        buyerMessage = persistMessage(conversation, buyer, "Own buyer message",
                base.plusSeconds(20), UUID.randomUUID());
        persistMessage(statusConversation, primaryAgent, "Historical status message",
                base.plusSeconds(5), UUID.randomUUID());

        persistReadState(conversation, buyer, newerAgentMessage, base.plusSeconds(20));
        persistReadState(conversation, primaryAgent, olderAgentMessage, base.plusSeconds(10));
    }

    @AfterTransaction
    void cleanUp() {
        List<UUID> allConversationIds = new ArrayList<>();
        for (UUID propertyId : createdPropertyIds) {
            allConversationIds.addAll(jdbcTemplate.queryForList(
                    "SELECT id FROM conversations WHERE property_id = ?", UUID.class, propertyId));
        }
        for (UUID conversationId : allConversationIds) {
            jdbcTemplate.update(
                    "DELETE FROM conversation_read_states WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID entityId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", entityId);
        }
        for (UUID conversationId : allConversationIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", conversationId);
        }
        for (UUID conversationId : allConversationIds) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID propertyId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        jdbcTemplate.update("DELETE FROM property_subtypes WHERE id = ?", subtype.getId());
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
            rateLimitService.reset(
                    RateLimitService.SCOPE_AUTHENTICATED_API, "user:" + userId);
        }
    }

    @Test
    void activeBuyerCreatesPermanentConversationWithTwoReadStatesAndOneAuditRow() throws Exception {
        MvcResult created = mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", publishedProperty.getId()),
                        buyer, "BUYER", true))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.buyerId").value(buyer.getId().toString()))
                .andExpect(jsonPath("$.data.assignedAgentId").value(primaryAgent.getId().toString()))
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("data").get("conversationId").asText());

        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                buyer.getId(), publishedProperty.getId())).isPresent();
        assertThat(conversationReadStateRepository.findByConversationIdAndUserId(
                conversationId, buyer.getId())).isPresent();
        assertThat(conversationReadStateRepository.findByConversationIdAndUserId(
                conversationId, primaryAgent.getId())).isPresent();
        assertThat(countRows(
                "SELECT COUNT(*) FROM conversation_read_states WHERE conversation_id = ?",
                conversationId)).isEqualTo(2L);
        assertThat(countAudit(AuditEventService.ACTION_CONVERSATION_CREATE, conversationId))
                .isEqualTo(1L);

        MvcResult repeated = mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", publishedProperty.getId()),
                        buyer, "BUYER", true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        assertThat(objectMapper.readTree(repeated.getResponse().getContentAsString())
                .get("data").get("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(countRows("SELECT COUNT(*) FROM conversations WHERE id = ?", conversationId))
                .isEqualTo(1L);
        assertThat(countRows(
                "SELECT COUNT(*) FROM conversation_read_states WHERE conversation_id = ?",
                conversationId)).isEqualTo(2L);
        assertThat(countAudit(AuditEventService.ACTION_CONVERSATION_CREATE, conversationId))
                .isEqualTo(1L);
    }

    @Test
    void conversationCreationRejectsUnknownFieldsWithoutCreatingConversation() throws Exception {
        String smuggled = """
                {"initialMessage":"never persisted","buyerId":"%s","agentId":"%s","status":"CLOSED"}
                """.formatted(otherBuyer.getId(), primaryAgent.getId());

        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", publishedProperty.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(smuggled),
                        otherBuyer, "BUYER", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("initialMessage")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("buyerId")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("agentId")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));

        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                otherBuyer.getId(), publishedProperty.getId())).isEmpty();
    }

    @Test
    void newCreationFailsClosedForUnpublishedPropertyOrInactivePrimaryAgent() throws Exception {
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", draftProperty.getId()),
                        buyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations",
                                inactiveAgentProperty.getId()),
                        buyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                buyer.getId(), draftProperty.getId())).isEmpty();
        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                buyer.getId(), inactiveAgentProperty.getId())).isEmpty();
        assertThat(countRows(
                "SELECT COUNT(*) FROM conversations WHERE property_id IN (?, ?)",
                draftProperty.getId(), inactiveAgentProperty.getId())).isZero();
    }

    @Test
    void conversationReadsAreScopedToTheOriginalBuyerAndAgent() throws Exception {
        // The list route has no resource ID for a security matcher to inspect:
        // other buyers/agents receive 200 with an empty participant-scoped list,
        // and ADMIN is denied by the matcher. Detail/history routes are role-
        // allowed for any BUYER/AGENT, so non-participants get the same generic
        // 404 as a missing ID; ADMIN gets a matcher-level 403.
        mockMvc.perform(authenticated(get("/api/v1/conversations"), buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data[*].conversationId",
                        hasItem(conversation.getId().toString())));
        mockMvc.perform(authenticated(get("/api/v1/conversations"), primaryAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].conversationId",
                        hasItem(conversation.getId().toString())));
        mockMvc.perform(authenticated(get("/api/v1/conversations"), otherBuyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(authenticated(get("/api/v1/conversations"), otherAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(authenticated(get("/api/v1/conversations"), admin, "ADMIN", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversation.getId().toString()));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        primaryAgent, "AGENT", false))
                .andExpect(status().isOk());
        for (User outsider : List.of(otherBuyer, otherAgent)) {
            String role = outsider == otherBuyer ? "BUYER" : "AGENT";
            mockMvc.perform(authenticated(
                            get("/api/v1/conversations/{conversationId}", conversation.getId()),
                            outsider, role, false))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        admin, "ADMIN", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", conversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", conversation.getId()),
                        primaryAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        for (User outsider : List.of(otherBuyer, otherAgent)) {
            String role = outsider == otherBuyer ? "BUYER" : "AGENT";
            mockMvc.perform(authenticated(
                            get("/api/v1/conversations/{conversationId}/messages", conversation.getId()),
                            outsider, role, false))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", conversation.getId()),
                        admin, "ADMIN", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        UUID missing = UUID.randomUUID();
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", missing), buyer, "BUYER", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", missing),
                        buyer, "BUYER", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", missing)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(newerAgentMessage.getId())),
                        buyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void readStatePatchIsLimitedToParticipantsAndRejectsUnknownFields() throws Exception {
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(newerAgentMessage.getId())),
                        buyer, "BUYER", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversation.getId().toString()));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(buyerMessage.getId())),
                        primaryAgent, "AGENT", true))
                .andExpect(status().isOk());

        // Non-participant agents/buyers are role-allowed at the matcher, so the
        // service fail-closed result is the generic 404. ADMIN is matcher-denied.
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(buyerMessage.getId())),
                        otherBuyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(buyerMessage.getId())),
                        otherAgent, "AGENT", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(buyerMessage.getId())),
                        admin, "ADMIN", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        String smuggled = """
                {"lastReadMessageId":"%s","userId":"%s","status":"CLOSED"}
                """.formatted(buyerMessage.getId(), otherBuyer.getId());
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(smuggled),
                        buyer, "BUYER", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("userId")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));

        entityManager.flush();
        entityManager.clear();
        assertThat(conversationReadStateRepository.findByConversationIdAndUserId(
                conversation.getId(), buyer.getId()).orElseThrow().getLastReadMessage().getId())
                .isEqualTo(newerAgentMessage.getId());
    }

    @Test
    void everyChatResponseIsPrivateAndMarkedNoStore() throws Exception {
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", publishedProperty.getId()),
                        buyer, "BUYER", true))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(get("/api/v1/conversations"), buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", conversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(newerAgentMessage.getId())),
                        buyer, "BUYER", true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", UUID.randomUUID()),
                        buyer, "BUYER", false))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        admin, "ADMIN", false))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void mutationsRequireCsrfAndReadStateRejectsUnknownFieldsWithoutMutation() throws Exception {
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", publishedProperty.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(buyerMessage.getId())),
                        buyer, "BUYER", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                buyer.getId(), publishedProperty.getId())).isEmpty();

        long auditsBefore = countAuditForActor(
                AuditEventService.ACTION_READ_STATE_UPDATE, conversation.getId(), buyer.getId());
        String smuggled = """
                {"lastReadMessageId":"%s","actorId":"%s"}
                """.formatted(buyerMessage.getId(), otherBuyer.getId());
        mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(smuggled),
                        buyer, "BUYER", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("actorId")));

        entityManager.flush();
        entityManager.clear();
        ConversationReadState buyerState = conversationReadStateRepository
                .findByConversationIdAndUserId(conversation.getId(), buyer.getId()).orElseThrow();
        assertThat(buyerState.getLastReadMessage().getId()).isEqualTo(newerAgentMessage.getId());
        assertThat(countAuditForActor(
                AuditEventService.ACTION_READ_STATE_UPDATE, conversation.getId(), buyer.getId()))
                .isEqualTo(auditsBefore);
    }

    @Test
    void historyValidatesLimitMutuallyExclusiveCursorsAndMalformedCursor() throws Exception {
        String history = "/api/v1/conversations/" + conversation.getId() + "/messages";
        mockMvc.perform(authenticated(get(history).param("limit", "0"), buyer, "BUYER", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(authenticated(get(history).param("limit", "101"), buyer, "BUYER", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(authenticated(get(history)
                        .param("before", "cursor").param("after", "cursor"),
                        buyer, "BUYER", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(authenticated(get(history).param("before", "not-a-cursor!"),
                        buyer, "BUYER", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void readStateNeverRegressesOwnMessagesAreExcludedAndPeerStateStaysPrivate() throws Exception {
        long auditsBefore = countAuditForActor(
                AuditEventService.ACTION_READ_STATE_UPDATE, conversation.getId(), buyer.getId());
        MvcResult detail = mockMvc.perform(authenticated(
                        patch("/api/v1/conversations/{conversationId}/read-state", conversation.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readStateJson(olderAgentMessage.getId())),
                        buyer, "BUYER", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadMessageId").doesNotExist())
                .andExpect(jsonPath("$.data.readStates").doesNotExist())
                .andExpect(jsonPath("$.data.otherParticipantReadState").doesNotExist())
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andReturn();

        entityManager.flush();
        entityManager.clear();
        ConversationReadState buyerState = conversationReadStateRepository
                .findByConversationIdAndUserId(conversation.getId(), buyer.getId()).orElseThrow();
        assertThat(buyerState.getLastReadMessage().getId()).isEqualTo(newerAgentMessage.getId());
        assertThat(countAuditForActor(
                AuditEventService.ACTION_READ_STATE_UPDATE, conversation.getId(), buyer.getId()))
                .isEqualTo(auditsBefore);
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("data").get("lastMessageId").asText()).isEqualTo(buyerMessage.getId().toString());

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", conversation.getId()),
                        primaryAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.lastReadMessageId").doesNotExist());
    }

    @Test
    void propertyReassignmentLeavesConversationAssignmentAndAccessUnchanged() throws Exception {
        Property property = propertyRepository.findById(reassignmentProperty.getId()).orElseThrow();
        property.setPrimaryAgent(otherAgent);
        propertyRepository.saveAndFlush(property);
        entityManager.flush();
        entityManager.clear();

        Conversation stored = conversationRepository.findById(reassignmentConversation.getId())
                .orElseThrow();
        assertThat(stored.getAssignedAgent().getId()).isEqualTo(primaryAgent.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT assigned_agent_id::text FROM conversations WHERE id = ?",
                String.class, stored.getId())).isEqualTo(primaryAgent.getId().toString());

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", stored.getId()),
                        primaryAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAgentId").value(primaryAgent.getId().toString()));
        // The matcher only knows the AGENT role, so the newly assigned agent's
        // denial is the service-layer generic 404 for a non-participant.
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", stored.getId()),
                        otherAgent, "AGENT", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void statusChangesPreserveHistoryButBlockNewConversationCreation() throws Exception {
        // Creation runs in REQUIRES_NEW, so the eligibility-changing status
        // update must be committed before the HTTP call.
        updatePropertyStatusCommitted(statusProperty.getId(), PropertyStatus.SOLD);

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", statusConversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk());
        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}/messages", statusConversation.getId()),
                        primaryAgent, "AGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("Historical status message"));
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", statusProperty.getId()),
                        otherBuyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        updatePropertyStatusCommitted(statusProperty.getId(), PropertyStatus.ARCHIVED);

        mockMvc.perform(authenticated(
                        get("/api/v1/conversations/{conversationId}", statusConversation.getId()),
                        buyer, "BUYER", false))
                .andExpect(status().isOk());
        mockMvc.perform(authenticated(
                        post("/api/v1/properties/{propertyId}/conversations", statusProperty.getId()),
                        otherBuyer, "BUYER", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(conversationRepository.findByBuyerIdAndPropertyId(
                otherBuyer.getId(), statusProperty.getId())).isEmpty();
    }

    @Test
    void chatRoutesConsumeTheExistingAuthenticatedApiRateLimitBucket() throws Exception {
        String identity = "user:" + buyer.getId();
        rateLimitService.reset(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        try {
            rateLimitService.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
            rateLimitService.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, identity);

            // No helper reset here: the filter must see the exhausted shared
            // bucket keyed by the server-side principal and return 429.
            mockMvc.perform(get("/api/v1/conversations")
                            .with(authentication(session(buyer, "BUYER"))))
                    .andExpect(status().is(429))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
        } finally {
            rateLimitService.reset(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        }
    }

    // ------------------------------------------------------------------
    // Fixture persistence
    // ------------------------------------------------------------------

    private User persistUser(String roleName, String label, AccountStatus status) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage73");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPasswordHash("$2a$10$not-a-real-password-hash");
        user.setAccountStatus(status);
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private Property persistProperty(String label, PropertyStatus status, User primaryAgent) {
        Property property = new Property();
        property.setTitle(unique("Chat " + label));
        property.setSlug(unique("chat-" + label));
        property.setDescription("Stage 7.3 REST acceptance fixture.");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Chat Road");
        property.setLocality(unique("Locality"));
        property.setCity("Bengaluru");
        property.setState("Karnataka");
        property.setCountry("India");
        property.setPostalCode("560001");
        property.setLatitude(new BigDecimal("12.9715990"));
        property.setLongitude(new BigDecimal("77.5945660"));
        property.setStatus(status);
        property.setPrimaryAgent(primaryAgent);
        property.setCreatedBy(admin);
        property.setUpdatedBy(admin);
        property.setPublishedAt(status == PropertyStatus.PUBLISHED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());
        return property;
    }

    private void updatePropertyStatusCommitted(UUID propertyId, PropertyStatus status) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(ignored -> jdbcTemplate.update(
                "UPDATE properties SET status = ?, updated_at = now() WHERE id = ?",
                status.name(), propertyId));
        entityManager.clear();
    }

    private Conversation persistConversation(User conversationBuyer, User agent, Property property) {
        Conversation entry = new Conversation();
        entry.setBuyer(conversationBuyer);
        entry.setProperty(property);
        entry.setAssignedAgent(agent);
        entry.setStatus("ACTIVE");
        conversationRepository.saveAndFlush(entry);
        return entry;
    }

    private Message persistMessage(
            Conversation owner, User sender, String content, Instant sentAt, UUID idempotencyKey) {
        Message message = new Message();
        message.setConversation(owner);
        message.setSender(sender);
        message.setMessageType("TEXT");
        message.setContent(content);
        message.setSentAt(sentAt);
        message.setIdempotencyKey(idempotencyKey);
        messageRepository.saveAndFlush(message);
        return message;
    }

    private void persistReadState(
            Conversation owner, User user, Message lastRead, Instant lastReadAt) {
        ConversationReadState state = new ConversationReadState();
        state.setId(new ConversationReadStateId(owner.getId(), user.getId()));
        state.setConversation(owner);
        state.setUser(user);
        state.setLastReadMessage(lastRead);
        state.setLastReadAt(lastReadAt);
        conversationReadStateRepository.saveAndFlush(state);
    }

    // ------------------------------------------------------------------
    // HTTP and assertion helpers
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request, User user, String role, boolean withCsrf) {
        rateLimitService.reset(RateLimitService.SCOPE_AUTHENTICATED_API, "user:" + user.getId());
        request.header(HttpHeaders.ORIGIN, ORIGIN)
                .with(authentication(session(user, role)));
        if (withCsrf) {
            request.with(csrf());
        }
        return request;
    }

    private static SessionAuthenticationToken session(User user, String role) {
        return new SessionAuthenticationToken(user.getId().toString(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static String readStateJson(UUID lastReadMessageId) {
        return """
                {"lastReadMessageId":"%s"}
                """.formatted(lastReadMessageId);
    }

    private long countAudit(String action, UUID entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
        return count == null ? 0L : count;
    }

    private long countAuditForActor(String action, UUID entityId, UUID actorId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs "
                        + "WHERE action = ? AND entity_id = ? AND actor_id = ?",
                Long.class, action, entityId, actorId);
        return count == null ? 0L : count;
    }

    private long countRows(String sql, Object... arguments) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return count == null ? 0L : count;
    }

    private static String unique(String label) {
        return PREFIX + label + "-" + UUID.randomUUID();
    }
}
