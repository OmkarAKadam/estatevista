package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyCommercialDetails;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyCommercialDetailsRepository;
import com.estatevista.property.repository.PropertyImageRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertyResidentialDetailsRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ReflectionUtils;

/**
 * Real-PostgreSQL verification for the Stage 4.6 property lifecycle slice.
 *
 * <p>Every request uses a real session cookie and the server-issued CSRF token.
 * No H2 database, Testcontainers, mock authentication, or standalone controller
 * slice is used. Properties are seeded directly so each transition test starts
 * from a known, publication-ready state without depending on other stages.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=6")
class PropertyLifecycleAndModerationIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final UUID RESIDENTIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID COMMERCIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

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
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;

    @Autowired
    private PropertyCommercialDetailsRepository commercialDetailsRepository;

    @Autowired
    private PropertyImageRepository imageRepository;

    @Autowired
    private PropertyService propertyService;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubtypeIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
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
            for (UUID imageId : jdbcTemplate.queryForList(
                    "SELECT id FROM property_images WHERE property_id = ?",
                    UUID.class, propertyId)) {
                jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", imageId);
            }
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID propertyId : propertyIds) {
            jdbcTemplate.update("DELETE FROM property_images WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM property_amenities WHERE property_id = ?", propertyId);
            jdbcTemplate.update(
                    "DELETE FROM property_residential_details WHERE property_id = ?", propertyId);
            jdbcTemplate.update(
                    "DELETE FROM property_commercial_details WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID subtypeId : createdSubtypeIds) {
            jdbcTemplate.update("DELETE FROM property_subtypes WHERE id = ?", subtypeId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    // ------------------------------------------------------------------
    // Legal transitions, timestamps, visibility, and response envelope
    // ------------------------------------------------------------------

    @Test
    void adminPublishesCompleteDraftWithEnvelopeAuditAndVisibility() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session admin = login(fixture.admin().getEmail());

        JsonNode data = readData(mockMvc.perform(statusRequest(admin, property.getId(),
                        statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(property.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.meta").doesNotExist())
                .andReturn());

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isNotNull();
        assertThat(stored.getUpdatedBy().getId()).isEqualTo(fixture.admin().getId());
        assertThat(stored.getArchivedAt()).isNull();
        assertThat(data.at("/publishedAt").asText()).isNotBlank();
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_STATUS_CHANGED, property.getId()))
                .isEqualTo(1);
        assertLifecycleAudit(property.getId(), "DRAFT", "PUBLISHED", "ACCEPTED",
                fixture.admin().getId());
        expectPublicVisible(property, true);
        assertInRecent(property.getId(), true);
    }

    @Test
    void assignedAgentPublishesOwnCompleteDraft() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.PUBLISHED);
        assertLifecycleAudit(property.getId(), "DRAFT", "PUBLISHED", "ACCEPTED",
                fixture.agent().getId());
    }

    @Test
    void publishedToUnavailablePreservesPublishedAtAndHidesListing() throws Exception {
        Fixture fixture = seedFixture();
        Instant original = Instant.now().minus(30, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MICROS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, original, true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("UNAVAILABLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.publishedAt").value(original.toString()));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.UNAVAILABLE);
        assertThat(stored.getPublishedAt()).isEqualTo(original);
        assertLifecycleAudit(property.getId(), "PUBLISHED", "UNAVAILABLE", "ACCEPTED",
                fixture.agent().getId());
        expectPublicVisible(property, false);
        assertInRecent(property.getId(), false);
    }

    @Test
    void unavailableToPublishedRefreshesPublishedAtAndRestoresVisibility() throws Exception {
        Fixture fixture = seedFixture();
        Instant oldPublication = Instant.now().minus(2, ChronoUnit.HOURS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.UNAVAILABLE,
                ListingType.SALE, oldPublication, true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getPublishedAt()).isAfter(oldPublication);
        assertLifecycleAudit(property.getId(), "UNAVAILABLE", "PUBLISHED", "ACCEPTED",
                fixture.agent().getId());
        expectPublicVisible(property, true);
    }

    @Test
    void publishedToSoldRecordsSaleOutcomeAndHidesListing() throws Exception {
        Fixture fixture = seedFixture();
        Instant original = Instant.now().minus(10, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MICROS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, original, true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("SOLD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SOLD"))
                .andExpect(jsonPath("$.data.publishedAt").value(original.toString()));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.SOLD);
        assertThat(stored.getPublishedAt()).isEqualTo(original);
        assertLifecycleAudit(property.getId(), "PUBLISHED", "SOLD", "ACCEPTED",
                fixture.agent().getId());
        expectPublicVisible(property, false);
    }

    @Test
    void publishedToRentedRecordsRentalOutcomeAndHidesListing() throws Exception {
        Fixture fixture = seedFixture();
        Instant original = Instant.now().minus(10, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MICROS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.RENT, original, true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("RENTED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RENTED"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.RENTED);
        assertLifecycleAudit(property.getId(), "PUBLISHED", "RENTED", "ACCEPTED",
                fixture.agent().getId());
        expectPublicVisible(property, false);
    }

    @Test
    void adminReopensSoldAndRefreshesPublishedAt() throws Exception {
        Fixture fixture = seedFixture();
        Instant original = Instant.now().minus(5, ChronoUnit.HOURS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.SOLD,
                ListingType.SALE, original, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getPublishedAt()).isAfter(original);
        assertLifecycleAudit(property.getId(), "SOLD", "PUBLISHED", "ACCEPTED",
                fixture.admin().getId());
        expectPublicVisible(property, true);
    }

    @Test
    void adminReopensRentedAndRefreshesPublishedAt() throws Exception {
        Fixture fixture = seedFixture();
        Instant original = Instant.now().minus(5, ChronoUnit.HOURS);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.RENTED,
                ListingType.RENT, original, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getPublishedAt())
                .isAfter(original);
        assertLifecycleAudit(property.getId(), "RENTED", "PUBLISHED", "ACCEPTED",
                fixture.admin().getId());
    }

    @Test
    void commercialPropertyPublishesWithMatchingCommercialDetails() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistCommercial(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.RENT, null, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("COMMERCIAL"))
                .andExpect(jsonPath("$.data.commercialDetails.washrooms").value(2))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        expectPublicVisible(property, true);
    }

    // ------------------------------------------------------------------
    // Authorization, session, and moderated-error coverage
    // ------------------------------------------------------------------

    @Test
    void agentCannotReopenCompletedListings() throws Exception {
        Fixture fixture = seedFixture();
        Property sold = persistResidential(fixture, fixture.agent(), PropertyStatus.SOLD,
                ListingType.SALE, Instant.now().minusSeconds(60), true, true);
        Property rented = persistResidential(fixture, fixture.agent(), PropertyStatus.RENTED,
                ListingType.RENT, Instant.now().minusSeconds(60), true, true);
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(statusRequest(agent, sold.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(statusRequest(agent, rented.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(propertyRepository.findById(sold.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.SOLD);
        assertThat(propertyRepository.findById(rented.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.RENTED);
        assertLifecycleAudit(sold.getId(), "SOLD", "PUBLISHED", "FORBIDDEN",
                fixture.agent().getId());
        assertLifecycleAudit(rented.getId(), "RENTED", "PUBLISHED", "FORBIDDEN",
                fixture.agent().getId());
    }

    @Test
    void buyerAndUnassignedAgentCannotChangeStatus() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session buyer = login(fixture.buyer().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());

        mockMvc.perform(statusRequest(buyer, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(statusRequest(otherAgent, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
        assertLifecycleAudit(property.getId(), "DRAFT", "PUBLISHED", "FORBIDDEN",
                fixture.buyer().getId());
        assertLifecycleAudit(property.getId(), "DRAFT", "PUBLISHED", "FORBIDDEN",
                fixture.otherAgent().getId());
    }

    @Test
    void anonymousAndMissingCsrfCannotChangeStatus() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session anonymous = anonymousSession();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(patch("/api/v1/properties/{id}/status", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(anonymous.cookie())
                        .header(CSRF_HEADER, anonymous.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusJson("PUBLISHED")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch("/api/v1/properties/{id}/status", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusJson("PUBLISHED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
    }

    @Test
    void invalidUnsupportedAndArchivedTargetsReturnValidationError() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("NOT_A_STATUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));
        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("ARCHIVED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(statusRequest(admin, property.getId(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(statusRequest(admin, property.getId(),
                        "{\"status\":\"PUBLISHED\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("role")));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
        assertLifecycleAudit(property.getId(), "DRAFT", "UNKNOWN", "VALIDATION_ERROR",
                fixture.admin().getId());
    }

    @Test
    void missingAndMalformedPropertyIdentifiersAreHandled() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, UUID.randomUUID(), statusJson("PUBLISHED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/properties/not-a-uuid/status")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusJson("PUBLISHED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void sameStatusRequestsConflictAndLeaveTimestampsUnchanged() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, Instant.now().minusSeconds(120), true, true);
        Instant before = propertyRepository.findById(property.getId()).orElseThrow().getUpdatedAt();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getUpdatedAt()).isEqualTo(before);
        assertThat(stored.getUpdatedBy()).isNull();
        assertLifecycleAudit(property.getId(), "PUBLISHED", "PUBLISHED", "CONFLICT",
                fixture.admin().getId());
    }

    @Test
    void illegalTransitionsReturnConflictWithoutStateChange() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        List<PropertyStatus> targets = List.of(
                PropertyStatus.UNAVAILABLE, PropertyStatus.SOLD, PropertyStatus.RENTED);
        for (PropertyStatus target : targets) {
            Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                    ListingType.SALE, null, true, true);
            mockMvc.perform(statusRequest(admin, property.getId(), statusJson(target.name())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"));
            assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                    .isEqualTo(PropertyStatus.DRAFT);
        }

        for (ListingType listingType : List.of(ListingType.SALE, ListingType.RENT)) {
            Property property = persistResidential(fixture, fixture.agent(),
                    PropertyStatus.PUBLISHED, listingType, Instant.now(), true, true);
            mockMvc.perform(statusRequest(admin, property.getId(), statusJson("DRAFT")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"));
            assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                    .isEqualTo(PropertyStatus.PUBLISHED);
        }
        for (PropertyStatus source : List.of(PropertyStatus.SOLD, PropertyStatus.RENTED)) {
            Property property = persistResidential(fixture, fixture.agent(), source,
                    ListingType.SALE, Instant.now(), true, true);
            mockMvc.perform(statusRequest(admin, property.getId(), statusJson("UNAVAILABLE")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"));
            assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                    .isEqualTo(source);
        }
    }

    @Test
    void archivedPropertyIsTerminalForStatusEndpoint() throws Exception {
        Fixture fixture = seedFixture();
        Instant archivedAt = Instant.now().minusSeconds(30);
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.ARCHIVED,
                ListingType.SALE, null, true, true);
        jdbcTemplate.update("UPDATE properties SET archived_at = ? WHERE id = ?",
                java.sql.Timestamp.from(archivedAt), property.getId());
        Session admin = login(fixture.admin().getEmail());

        for (String target : List.of("PUBLISHED", "UNAVAILABLE", "DRAFT")) {
            mockMvc.perform(statusRequest(admin, property.getId(), statusJson(target)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        }

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.ARCHIVED);
        assertThat(stored.getArchivedAt()).isNotNull();
        assertThat(stored.getPublishedAt()).isNull();
        assertLifecycleAudit(property.getId(), "ARCHIVED", "PUBLISHED", "CONFLICT",
                fixture.admin().getId());
        expectPublicVisible(property, false);
    }

    @Test
    void outcomeMismatchReturnsValidationError() throws Exception {
        Fixture fixture = seedFixture();
        Property sale = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, Instant.now(), true, true);
        Property rent = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.RENT, Instant.now(), true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, sale.getId(), statusJson("RENTED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));
        mockMvc.perform(statusRequest(admin, rent.getId(), statusJson("SOLD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(propertyRepository.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.PUBLISHED);
        assertThat(propertyRepository.findById(rent.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.PUBLISHED);
        assertLifecycleAudit(sale.getId(), "PUBLISHED", "RENTED", "VALIDATION_ERROR",
                fixture.admin().getId());
    }

    // ------------------------------------------------------------------
    // Publication prerequisites and transactional integrity
    // ------------------------------------------------------------------

    @Test
    void missingOrWrongCategoryDetailRowReturnsUnprocessable() throws Exception {
        Fixture fixture = seedFixture();
        Property missing = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, false, true);
        Property wrong = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, false, true);
        PropertyCommercialDetails commercial = new PropertyCommercialDetails();
        commercial.setProperty(wrong);
        commercial.setParkingSpaces((short) 1);
        commercialDetailsRepository.saveAndFlush(commercial);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, missing.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(hasItem("residentialDetails")));
        mockMvc.perform(statusRequest(admin, wrong.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(hasItem("residentialDetails")));

        assertThat(propertyRepository.findById(missing.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
        assertLifecycleAudit(missing.getId(), "DRAFT", "PUBLISHED", "UNPROCESSABLE_ENTITY",
                fixture.admin().getId());
    }

    @Test
    void publicationRequiresAtLeastOneImageWithExactlyOnePrimary() throws Exception {
        Fixture fixture = seedFixture();
        Property noImages = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, false);
        Property noPrimary = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, false);
        addImage(noPrimary, false);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, noImages.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("images")));
        mockMvc.perform(statusRequest(admin, noPrimary.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("images")));

        assertThat(imageRepository.countByProperty_Id(noImages.getId())).isZero();
        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(noPrimary.getId())).isEmpty();
        assertThat(propertyRepository.findById(noPrimary.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
    }

    @Test
    void inactivePrimaryAgentBlocksPublication() throws Exception {
        Fixture fixture = seedFixture();
        User inactive = persistUser("AGENT", "inactive-agent", AccountStatus.INACTIVE);
        Property property = persistResidential(fixture, inactive, PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(hasItem("primaryAgentId")));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.DRAFT);
    }

    @Test
    void incompleteRequiredFieldsReturnUnprocessableWithoutPartialMutation() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        property.setDescription("   ");
        property.setCity("");
        property.setCurrencyCode("ZZZ");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(null);
        propertyRepository.saveAndFlush(property);
        Instant updatedAt = propertyRepository.findById(property.getId()).orElseThrow().getUpdatedAt();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(hasItem("description")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("city")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("currencyCode")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("location")));

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.DRAFT);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getUpdatedBy()).isNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void zeroPriceFailsPublicationPrerequisiteValidation() {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        property.setPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> invokePublicationPrerequisiteValidation(property))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getDetails())
                        .extracting(detail -> detail.field())
                        .contains("price"));
    }

    @Test
    void auditPayloadContainsOnlySafeLifecycleFacts() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk());
        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isConflict());

        String before = lifecycleAuditField(property.getId(), "before_data", 0);
        String after = lifecycleAuditField(property.getId(), "after_data", 0);
        String rejected = lifecycleAuditField(property.getId(), "after_data", 1);
        assertThat(before).contains("DRAFT").contains("SUCCESS");
        assertThat(after).contains("PUBLISHED").contains("ACCEPTED");
        assertThat(rejected).contains("CONFLICT");
        assertThat(before + after + rejected)
                .doesNotContain(CSRF_HEADER, SESSION_COOKIE, PASSWORD, "passwordHash");
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_STATUS_CHANGED, property.getId()))
                .isEqualTo(2);
    }

    @Test
    void concurrentTransitionsProduceExactlyOneWinner() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, Instant.now().minusSeconds(30), true, true);
        Session admin = login(fixture.admin().getEmail());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String target : List.of("UNAVAILABLE", "SOLD")) {
            futures.add(executor.submit(() -> {
                start.await();
                return mockMvc.perform(statusRequest(admin, property.getId(), statusJson(target)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isIn(PropertyStatus.UNAVAILABLE, PropertyStatus.SOLD);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_STATUS_CHANGED, property.getId()))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Existing archive endpoint remains the only ARCHIVED path
    // ------------------------------------------------------------------

    @Test
    void archivalRemainsAdminOnlyTerminalAndHidden() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.PUBLISHED,
                ListingType.SALE, Instant.now().minusSeconds(60), true, true);
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());

        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie()).header(CSRF_HEADER, agent.csrfToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.ARCHIVED);
        assertThat(stored.getArchivedAt()).isNotNull();
        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        expectPublicVisible(property, false);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ARCHIVE, property.getId()))
                .isEqualTo(1);
    }

    @Test
    void fullLifecycleRegressionPreservesVisibilityRules() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistResidential(fixture, fixture.agent(), PropertyStatus.DRAFT,
                ListingType.SALE, null, true, true);
        Session agent = login(fixture.agent().getEmail());
        Session admin = login(fixture.admin().getEmail());

        expectPublicVisible(property, false);
        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk());
        expectPublicVisible(property, true);
        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("UNAVAILABLE")))
                .andExpect(status().isOk());
        expectPublicVisible(property, false);
        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk());
        expectPublicVisible(property, true);
        mockMvc.perform(statusRequest(agent, property.getId(), statusJson("SOLD")))
                .andExpect(status().isOk());
        expectPublicVisible(property, false);
        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("PUBLISHED")))
                .andExpect(status().isOk());
        expectPublicVisible(property, true);
        mockMvc.perform(statusRequest(admin, property.getId(), statusJson("UNAVAILABLE")))
                .andExpect(status().isOk());
        expectPublicVisible(property, false);

        String statuses = jdbcTemplate.queryForObject(
                "SELECT string_agg(action || ':' || after_data::text, '|' ORDER BY created_at, id) "
                        + "FROM audit_logs WHERE entity_id = ? AND action = ?",
                String.class, property.getId(),
                AuditEventService.ACTION_PROPERTY_STATUS_CHANGED);
        assertThat(statuses).contains("PUBLISHED", "UNAVAILABLE", "SOLD");
        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private record Fixture(
            User admin,
            User agent,
            User otherAgent,
            User buyer,
            PropertySubtype residentialSubtype,
            PropertySubtype commercialSubtype) {
    }

    private record Session(Cookie cookie, String csrfToken) {
    }

    private Fixture seedFixture() {
        User admin = persistUser("ADMIN", "admin", AccountStatus.ACTIVE);
        User agent = persistUser("AGENT", "agent", AccountStatus.ACTIVE);
        User otherAgent = persistUser("AGENT", "other-agent", AccountStatus.ACTIVE);
        User buyer = persistUser("BUYER", "buyer", AccountStatus.ACTIVE);
        PropertySubtype residentialSubtype =
                persistSubtype(residentialCategory(), "Lifecycle Flat");
        PropertySubtype commercialSubtype =
                persistSubtype(commercialCategory(), "Lifecycle Office");
        return new Fixture(admin, agent, otherAgent, buyer, residentialSubtype, commercialSubtype);
    }

    private User persistUser(String roleName, String label, AccountStatus status) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage46");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(status);
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private PropertySubtype persistSubtype(PropertyCategory category, String label) {
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(category);
        subtype.setName(unique(label));
        subtypeRepository.saveAndFlush(subtype);
        createdSubtypeIds.add(subtype.getId());
        return subtype;
    }

    private PropertyCategory residentialCategory() {
        return categoryRepository.findById(RESIDENTIAL_CATEGORY_ID).orElseThrow();
    }

    private PropertyCategory commercialCategory() {
        return categoryRepository.findById(COMMERCIAL_CATEGORY_ID).orElseThrow();
    }

    private Property persistResidential(
            Fixture fixture, User agent, PropertyStatus status, ListingType listingType,
            Instant publishedAt, boolean withDetails, boolean primaryImage) {
        return persistProperty(fixture, agent, residentialCategory(), fixture.residentialSubtype(),
                status, listingType, publishedAt, withDetails, primaryImage);
    }

    private Property persistCommercial(
            Fixture fixture, User agent, PropertyStatus status, ListingType listingType,
            Instant publishedAt, boolean withDetails, boolean primaryImage) {
        return persistProperty(fixture, agent, commercialCategory(), fixture.commercialSubtype(),
                status, listingType, publishedAt, withDetails, primaryImage);
    }

    private Property persistProperty(
            Fixture fixture, User agent, PropertyCategory category, PropertySubtype subtype,
            PropertyStatus status, ListingType listingType, Instant publishedAt,
            boolean withDetails, boolean primaryImage) {
        Property property = new Property();
        property.setTitle(unique("Lifecycle Listing"));
        property.setSlug(unique("lifecycle-listing"));
        property.setDescription("Stage 4.6 lifecycle integration fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(listingType);
        property.setPrice(listingType == ListingType.RENT
                ? new BigDecimal("45000.00") : new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Lifecycle Road");
        property.setLocality(unique("Locality"));
        property.setCity(unique("City"));
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(new BigDecimal("73.2000000"));
        property.setStatus(status);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(fixture.admin());
        property.setPublishedAt(publishedAt);
        property.setArchivedAt(status == PropertyStatus.ARCHIVED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());

        if (withDetails) {
            if ("RESIDENTIAL".equals(category.getName())) {
                PropertyResidentialDetails details = new PropertyResidentialDetails();
                details.setProperty(property);
                details.setBedrooms((short) 2);
                details.setBathrooms((short) 2);
                residentialDetailsRepository.saveAndFlush(details);
            } else {
                PropertyCommercialDetails details = new PropertyCommercialDetails();
                details.setProperty(property);
                details.setParkingSpaces((short) 2);
                details.setWashrooms((short) 2);
                commercialDetailsRepository.saveAndFlush(details);
            }
        }
        if (primaryImage) {
            addImage(property, true);
        }
        return property;
    }

    private void addImage(Property property, boolean primary) {
        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageProvider("TEST_FAKE");
        image.setStoragePublicId("estatevista/properties/" + property.getId() + "/"
                + UUID.randomUUID());
        image.setImageUrl("https://images.example.invalid/" + UUID.randomUUID() + ".png");
        image.setSortOrder(0);
        image.setIsPrimary(primary);
        imageRepository.saveAndFlush(image);
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

    private Session anonymousSession() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(
                requireSessionCookie(csrfResult),
                csrfResult.getResponse().getHeader(CSRF_HEADER));
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

    private MockHttpServletRequestBuilder statusRequest(
            Session session, UUID propertyId, String body) {
        return patch("/api/v1/properties/{id}/status", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                .cookie(session.cookie())
                .header(CSRF_HEADER, session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String statusJson(String status) {
        return "{\"status\":\"" + status + "\"}";
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private void expectPublicVisible(Property property, boolean visible) throws Exception {
        var request = get("/api/v1/properties/{id}", property.getId());
        if (visible) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status")
                            .value(propertyStatusAtRequest(property).name()));
            JsonNode search = objectMapper.readTree(mockMvc.perform(get("/api/v1/properties")
                            .param("city", property.getCity())
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(search.at("/data").toString()).contains(property.getId().toString());
        } else {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            JsonNode search = objectMapper.readTree(mockMvc.perform(get("/api/v1/properties")
                            .param("city", property.getCity())
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(search.at("/data").toString()).doesNotContain(property.getId().toString());
        }
    }

    private PropertyStatus propertyStatusAtRequest(Property property) {
        return propertyRepository.findById(property.getId()).orElseThrow().getStatus();
    }

    private void assertInRecent(UUID propertyId, boolean expected) throws Exception {
        JsonNode recent = objectMapper.readTree(mockMvc.perform(get("/api/v1/properties/recent")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(recent.at("/data").toString().contains(propertyId.toString())).isEqualTo(expected);
    }

    private long countAudit(String action, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, propertyId);
        return count == null ? 0L : count;
    }

    private void assertLifecycleAudit(
            UUID propertyId, String from, String to, String result, UUID actorId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ? "
                        + "AND actor_id = ? AND before_data::text LIKE ? "
                        + "AND after_data::text LIKE ?",
                Long.class,
                AuditEventService.ACTION_PROPERTY_STATUS_CHANGED,
                propertyId,
                actorId,
                "%" + from + "%",
                "%" + to + "%");
        assertThat(count).as("lifecycle audit %s -> %s (%s)", from, to, result).isPositive();
        if ("REJECTED".equals(result) || "FORBIDDEN".equals(result)
                || "CONFLICT".equals(result) || "VALIDATION_ERROR".equals(result)
                || "UNPROCESSABLE_ENTITY".equals(result)) {
            String after = jdbcTemplate.queryForObject(
                    "SELECT after_data::text FROM audit_logs WHERE action = ? AND entity_id = ? "
                            + "AND actor_id = ? AND after_data::text LIKE ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    String.class,
                    AuditEventService.ACTION_PROPERTY_STATUS_CHANGED,
                    propertyId,
                    actorId,
                    "%" + result + "%");
            assertThat(after).contains(result);
        }
    }

    private String lifecycleAuditField(UUID propertyId, String column, int index) {
        String sql = "SELECT " + column + "::text FROM audit_logs "
                + "WHERE action = ? AND entity_id = ? ORDER BY created_at, id OFFSET ? LIMIT 1";
        String value = jdbcTemplate.queryForObject(sql, String.class,
                AuditEventService.ACTION_PROPERTY_STATUS_CHANGED, propertyId, index);
        assertThat(value).isNotNull();
        return value;
    }

    private void invokePublicationPrerequisiteValidation(Property property) {
        var method = ReflectionUtils.findMethod(
                PropertyService.class, "requirePublicationPrerequisites", Property.class);
        assertThat(method).isNotNull();
        ReflectionUtils.makeAccessible(method);
        ReflectionUtils.invokeMethod(method, AopTestUtils.getTargetObject(propertyService), property);
    }

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }
}
