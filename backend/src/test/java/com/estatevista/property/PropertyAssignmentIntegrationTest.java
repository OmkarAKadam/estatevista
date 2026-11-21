package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.SessionAuthenticationToken;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.inquiry.entity.Inquiry;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.property.dto.PropertyAssignmentRequest;
import com.estatevista.property.dto.PropertyDetailResponse;
import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.AmenityRepository;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertyResidentialDetailsRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-PostgreSQL verification for Stage 4.3 ownership enforcement and
 * administrator-controlled primary-agent reassignment.
 *
 * <p>HTTP tests use real session cookies and the server-issued CSRF token.
 * Service-layer probes use the same session-principal authentication token
 * shape produced by login. No H2 database, Testcontainers, mock MVC, or
 * standalone controller slice is used.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PropertyAssignmentIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final UUID RESIDENTIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PropertyCategoryRepository categoryRepository;

    @Autowired
    private PropertySubtypeRepository subtypeRepository;

    @Autowired
    private AmenityRepository amenityRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private AuditEventService auditEventService;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubtypeIds = new ArrayList<>();
    private final List<UUID> createdAmenityIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdInquiryIds = new ArrayList<>();
    private final List<UUID> createdConversationIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        Set<UUID> propertyIds = new LinkedHashSet<>(createdPropertyIds);
        for (UUID userId : createdUserIds) {
            propertyIds.addAll(jdbcTemplate.queryForList(
                    "SELECT id FROM properties WHERE created_by = ?", UUID.class, userId));
        }
        for (UUID propertyId : propertyIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID conversationId : createdConversationIds) {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM conversation_read_states WHERE conversation_id = ?", conversationId);
        }
        for (UUID conversationId : createdConversationIds) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID inquiryId : createdInquiryIds) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE id = ?", inquiryId);
        }
        for (UUID propertyId : propertyIds) {
            jdbcTemplate.update("DELETE FROM property_residential_details WHERE property_id = ?",
                    propertyId);
            jdbcTemplate.update("DELETE FROM property_images WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM property_amenities WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID subtypeId : createdSubtypeIds) {
            jdbcTemplate.update("DELETE FROM property_subtypes WHERE id = ?", subtypeId);
        }
        for (UUID amenityId : createdAmenityIds) {
            jdbcTemplate.update("DELETE FROM amenities WHERE id = ?", amenityId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    @Test
    void adminReassignsPropertyWithApprovedEnvelopeAndAudit() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        auditEventService.recordPropertyCreated(property.getId(), fixture.admin().getId());
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(property.getId().toString()))
                .andExpect(jsonPath("$.data.primaryAgent.id")
                        .value(fixture.otherAgent().getId().toString()))
                .andExpect(jsonPath("$.data.primaryAgent.email").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAgent.phone").doesNotExist());

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getPrimaryAgent().getId()).isEqualTo(fixture.otherAgent().getId());
        assertThat(stored.getCreatedBy().getId()).isEqualTo(fixture.admin().getId());
        assertThat(stored.getUpdatedBy().getId()).isEqualTo(fixture.admin().getId());

        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isEqualTo(1);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_CREATE, property.getId()))
                .isEqualTo(1);
        String before = jdbcTemplate.queryForObject(
                "SELECT before_data::text FROM audit_logs WHERE action = ? AND entity_id = ?",
                String.class, AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId());
        String after = jdbcTemplate.queryForObject(
                "SELECT after_data::text FROM audit_logs WHERE action = ? AND entity_id = ?",
                String.class, AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId());
        String actor = jdbcTemplate.queryForObject(
                "SELECT actor_id::text FROM audit_logs WHERE action = ? AND entity_id = ?",
                String.class, AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId());
        assertThat(before).contains(fixture.agent().getId().toString());
        assertThat(after).contains(fixture.otherAgent().getId().toString());
        assertThat(actor).isEqualTo(fixture.admin().getId().toString());
        assertThat(before + after).doesNotContain(CSRF_HEADER, SESSION_COOKIE, PASSWORD);
    }

    @Test
    void nonAdminSessionsCannotReassign() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session agent = login(fixture.agent().getEmail());
        Session buyer = login(fixture.buyer().getEmail());
        String body = assignmentJson(fixture.otherAgent().getId());

        mockMvc.perform(assignmentRequest(agent, property.getId(), body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(assignmentRequest(buyer, property.getId(), body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment", property.getId())
                        .header(CSRF_HEADER, "anonymous-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isZero();
    }

    @Test
    void csrfTokenIsRequiredAndMustBeValid() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session admin = login(fixture.admin().getEmail());
        String body = assignmentJson(fixture.otherAgent().getId());

        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment", property.getId())
                        .cookie(admin.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment", property.getId())
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isZero();
    }

    @Test
    void assignmentValidatesAgentExistenceRoleAndActiveStatus() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(), assignmentJson(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));
        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.buyer().getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));
        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.inactiveAgent().getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
    }

    @Test
    void missingPropertyReturnsNotFound() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, UUID.randomUUID(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void sameAgentReassignmentConflicts() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.agent().getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isZero();
    }

    @Test
    void nullMissingAndUnknownFieldsAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        "{\"primaryAgentId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));
        mockMvc.perform(assignmentRequest(admin, property.getId(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));

        String untrustedFields = """
                {"primaryAgentId":"%s","actorId":"%s","updatedBy":"%s","role":"ADMIN"}
                """.formatted(fixture.otherAgent().getId(), fixture.buyer().getId(),
                fixture.buyer().getId());
        mockMvc.perform(assignmentRequest(admin, property.getId(), untrustedFields))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field", hasItem("actorId")))
                .andExpect(jsonPath("$.error.details[*].field", hasItem("updatedBy")))
                .andExpect(jsonPath("$.error.details[*].field", hasItem("role")));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getUpdatedBy())
                .isNull();
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isZero();
    }

    @Test
    void previousAgentLosesAndNewAgentGainsManagementAccess() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session previousAgent = login(fixture.agent().getEmail());
        Session newAgent = login(fixture.otherAgent().getEmail());
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(previousAgent.cookie())
                        .header(CSRF_HEADER, previousAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Before") + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(newAgent.cookie())
                        .header(CSRF_HEADER, newAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Denied Before") + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(previousAgent.cookie())
                        .header(CSRF_HEADER, previousAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Denied After") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        String newTitle = unique("Allowed After");
        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(newAgent.cookie())
                        .header(CSRF_HEADER, newAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + newTitle + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(newTitle));
    }

    @Test
    void crossAgentPatchAndPutAreDenied() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session otherAgent = login(fixture.otherAgent().getEmail());

        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(otherAgent.cookie())
                        .header(CSRF_HEADER, otherAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Cross Patch") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(put("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(otherAgent.cookie())
                        .header(CSRF_HEADER, otherAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Cross Put") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
    }

    @Test
    void genericPatchAndPutCannotReassignOwnership() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());
        String smuggled = """
                {"title":"%s","primaryAgentId":"%s"}
                """.formatted(unique("Smuggled"), fixture.otherAgent().getId());

        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smuggled))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));
        mockMvc.perform(put("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smuggled))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("primaryAgentId")));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isZero();
    }

    @Test
    void historicalInquiryAssignmentRemainsUnchanged() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        Inquiry inquiry = persistInquiry(fixture, property);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk());

        Inquiry stored = inquiryRepository.findById(inquiry.getId()).orElseThrow();
        assertThat(stored.getAssignedAgent().getId()).isEqualTo(fixture.agent().getId());
        assertThat(stored.getMessage()).isEqualTo("Historical inquiry message");
        String rawAgentId = jdbcTemplate.queryForObject(
                "SELECT assigned_agent_id::text FROM inquiries WHERE id = ?", String.class,
                inquiry.getId());
        assertThat(rawAgentId).isEqualTo(fixture.agent().getId().toString());
    }

    @Test
    void historicalConversationAssignmentAndMessagesRemainUnchanged() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        History history = persistHistory(fixture, property);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk());

        Conversation conversation =
                conversationRepository.findById(history.conversation().getId()).orElseThrow();
        Message message = messageRepository.findById(history.message().getId()).orElseThrow();
        assertThat(conversation.getAssignedAgent().getId()).isEqualTo(fixture.agent().getId());
        assertThat(message.getContent()).isEqualTo("Historical conversation message");
        String rawAgentId = jdbcTemplate.queryForObject(
                "SELECT assigned_agent_id::text FROM conversations WHERE id = ?", String.class,
                conversation.getId());
        assertThat(rawAgentId).isEqualTo(fixture.agent().getId().toString());
    }

    @Test
    void propertyCreationAndAuditHistoryRemainIntact() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        auditEventService.recordPropertyCreated(property.getId(), fixture.admin().getId());
        auditEventService.recordPropertyUpdated(property.getId(), fixture.agent().getId());
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk());

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getCreatedBy().getId()).isEqualTo(fixture.admin().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_CREATE, property.getId()))
                .isEqualTo(1);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_UPDATE, property.getId()))
                .isEqualTo(1);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isEqualTo(1);
        Long totalAuditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ?", Long.class,
                property.getId());
        assertThat(totalAuditRows).isEqualTo(3L);
    }

    @Test
    void archivedPropertyCanBeReassignedButArchivedAgentCannotModify() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.ARCHIVED);
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Archived Agent") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/properties/{propertyId}", property.getId())
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + unique("Archived Admin") + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mockMvc.perform(assignmentRequest(admin, property.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.primaryAgent.id")
                        .value(fixture.otherAgent().getId().toString()));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.otherAgent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ASSIGNMENT, property.getId()))
                .isEqualTo(1);
    }

    @Test
    void publicVisibilityAndHiddenExistenceRulesRemainUnchanged() throws Exception {
        Fixture fixture = seedFixture();
        Property published = persistProperty(fixture, fixture.agent(), PropertyStatus.PUBLISHED);
        Property archived = persistProperty(fixture, fixture.agent(), PropertyStatus.ARCHIVED);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(get("/api/v1/properties/{propertyId}", published.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.primaryAgent.id")
                        .value(fixture.agent().getId().toString()));
        mockMvc.perform(get("/api/v1/properties/{propertyId}", archived.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/properties/{propertyId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(assignmentRequest(admin, published.getId(),
                        assignmentJson(fixture.otherAgent().getId())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/properties/{propertyId}", published.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.primaryAgent.id")
                        .value(fixture.otherAgent().getId().toString()));
        mockMvc.perform(get("/api/v1/properties/{propertyId}", archived.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void serviceLayerAuthorizationIsEnforcedWithoutRequestBodyIdentity() {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        PropertyAssignmentRequest request = request(fixture.otherAgent().getId());

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), session(fixture.agent().getId(), "AGENT"), request))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), session(fixture.buyer().getId(), "BUYER"), request))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), anonymousSession(), request))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());

        PropertyDetailResponse result = propertyService.reassign(property.getId(),
                session(fixture.admin().getId(), "ADMIN"), request);
        assertThat(result.primaryAgent().id()).isEqualTo(fixture.otherAgent().getId());
        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.otherAgent().getId());
    }

    @Test
    void serviceLayerRejectsSameAgentAndIneligibleTargets() {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), PropertyStatus.DRAFT);
        var admin = session(fixture.admin().getId(), "ADMIN");

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), admin, request(fixture.agent().getId())))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), admin, request(fixture.buyer().getId())))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> propertyService.reassign(
                        property.getId(), admin, new PropertyAssignmentRequest()))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(currentAgentId(property.getId())).isEqualTo(fixture.agent().getId());
    }

    // ------------------------------------------------------------------
    // Fixture persistence
    // ------------------------------------------------------------------

    private Fixture seedFixture() {
        User admin = persistUser("ADMIN", "admin", AccountStatus.ACTIVE);
        User agent = persistUser("AGENT", "agent", AccountStatus.ACTIVE);
        User otherAgent = persistUser("AGENT", "other-agent", AccountStatus.ACTIVE);
        User inactiveAgent = persistUser("AGENT", "inactive-agent", AccountStatus.INACTIVE);
        User buyer = persistUser("BUYER", "buyer", AccountStatus.ACTIVE);
        PropertySubtype subtype = persistSubtype();
        Amenity amenity = persistAmenity();
        return new Fixture(admin, agent, otherAgent, inactiveAgent, buyer, subtype, amenity);
    }

    private User persistUser(String roleName, String label, AccountStatus status) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage43");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(status);
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private PropertySubtype persistSubtype() {
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(categoryRepository.findById(RESIDENTIAL_CATEGORY_ID).orElseThrow());
        subtype.setName(unique("Assignment Flat"));
        subtypeRepository.saveAndFlush(subtype);
        createdSubtypeIds.add(subtype.getId());
        return subtype;
    }

    private Amenity persistAmenity() {
        Amenity amenity = new Amenity();
        amenity.setName(unique("Assignment Amenity"));
        amenity.setDescription("Stage 4.3 integration amenity");
        amenityRepository.saveAndFlush(amenity);
        createdAmenityIds.add(amenity.getId());
        return amenity;
    }

    private Property persistProperty(Fixture fixture, User primaryAgent, PropertyStatus status) {
        Property property = new Property();
        property.setTitle(unique("Assignment Listing"));
        property.setSlug(unique("assignment-listing"));
        property.setDescription("Stage 4.3 integration fixture description");
        property.setCategory(categoryRepository.findById(RESIDENTIAL_CATEGORY_ID).orElseThrow());
        property.setSubtype(fixture.subtype());
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Assignment Road");
        property.setLocality(unique("Locality"));
        property.setCity(unique("City"));
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(new BigDecimal("73.2000000"));
        property.setStatus(status);
        property.setPrimaryAgent(primaryAgent);
        property.setCreatedBy(fixture.admin());
        property.setPublishedAt(status == PropertyStatus.PUBLISHED ? Instant.now() : null);
        property.setArchivedAt(status == PropertyStatus.ARCHIVED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());

        PropertyResidentialDetails details = new PropertyResidentialDetails();
        details.setProperty(property);
        details.setBedrooms((short) 2);
        details.setBathrooms((short) 2);
        residentialDetailsRepository.saveAndFlush(details);
        return property;
    }

    private Inquiry persistInquiry(Fixture fixture, Property property) {
        Inquiry inquiry = new Inquiry();
        inquiry.setBuyer(fixture.buyer());
        inquiry.setProperty(property);
        inquiry.setAssignedAgent(fixture.agent());
        inquiry.setSubject("Historical inquiry");
        inquiry.setMessage("Historical inquiry message");
        inquiry.setStatus("NEW");
        inquiryRepository.saveAndFlush(inquiry);
        createdInquiryIds.add(inquiry.getId());
        return inquiry;
    }

    private History persistHistory(Fixture fixture, Property property) {
        Inquiry inquiry = persistInquiry(fixture, property);
        Conversation conversation = new Conversation();
        conversation.setBuyer(fixture.buyer());
        conversation.setProperty(property);
        conversation.setAssignedAgent(fixture.agent());
        conversation.setStatus("ACTIVE");
        conversationRepository.saveAndFlush(conversation);
        createdConversationIds.add(conversation.getId());

        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(fixture.buyer());
        message.setMessageType("TEXT");
        message.setContent("Historical conversation message");
        messageRepository.saveAndFlush(message);
        return new History(inquiry, conversation, message);
    }

    // ------------------------------------------------------------------
    // HTTP, service, and database helpers
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder assignmentRequest(
            Session session, UUID propertyId, String body) {
        return patch("/api/v1/admin/properties/{propertyId}/assignment", propertyId)
                .cookie(session.cookie())
                .header(CSRF_HEADER, session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Session login(String email) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = requireSessionCookie(csrfResult);
        String csrfToken = csrfResult.getResponse().getHeader(CSRF_HEADER);
        assertThat(csrfToken).isNotBlank();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(csrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = requireSessionCookie(login);
        String rotatedToken = login.getResponse().getHeader(CSRF_HEADER);
        assertThat(rotatedToken).isNotBlank();
        return new Session(sessionCookie, rotatedToken);
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        createdSessionIds.add(sessionIdFromCookie(cookie));
        return cookie;
    }

    private static String sessionIdFromCookie(Cookie cookie) {
        String value = cookie.getValue();
        if (value.matches("[0-9a-fA-F-]{36}")) {
            return value;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            decoded = Base64.getMimeDecoder().decode(value);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private UUID currentAgentId(UUID propertyId) {
        return UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT primary_agent_id::text FROM properties WHERE id = ?", String.class,
                propertyId));
    }

    private long countAudit(String action, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, propertyId);
        return count == null ? 0L : count;
    }

    private static String assignmentJson(UUID agentId) {
        return """
                {"primaryAgentId":"%s"}
                """.formatted(agentId);
    }

    private static PropertyAssignmentRequest request(UUID agentId) {
        PropertyAssignmentRequest request = new PropertyAssignmentRequest();
        request.setPrimaryAgentId(agentId);
        return request;
    }

    private static SessionAuthenticationToken session(UUID accountId, String role) {
        return new SessionAuthenticationToken(accountId.toString(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static AnonymousAuthenticationToken anonymousSession() {
        return new AnonymousAuthenticationToken("stage43", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private record Session(Cookie cookie, String csrfToken) {
    }

    private record Fixture(
            User admin,
            User agent,
            User otherAgent,
            User inactiveAgent,
            User buyer,
            PropertySubtype subtype,
            Amenity amenity) {
    }

    private record History(Inquiry inquiry, Conversation conversation, Message message) {
    }
}
