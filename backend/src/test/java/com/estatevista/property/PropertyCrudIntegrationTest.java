package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyCommercialDetails;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.AmenityRepository;
import com.estatevista.property.repository.PropertyAmenityRepository;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real-PostgreSQL integration verification for the Stage 4.2 property CRUD slice.
 *
 * <p>Every test signs in through the real session endpoint and uses the server
 * issued CSRF token. No H2 database, Testcontainers, mock authentication, or
 * standalone controller slice is used.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PropertyCrudIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final String RUN_ID = UUID.randomUUID().toString();
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
    private AmenityRepository amenityRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;

    @Autowired
    private PropertyCommercialDetailsRepository commercialDetailsRepository;

    @Autowired
    private PropertyImageRepository imageRepository;

    @Autowired
    private PropertyAmenityRepository propertyAmenityRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubtypeIds = new ArrayList<>();
    private final List<UUID> createdAmenityIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        // Include any property created by a request whose assertion failed before
        // its ID could be tracked, so reference-data deletion cannot be blocked.
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
    void adminCreatesDraftPropertyWithTrustedIdentityAndAudit() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String title = unique("Created Listing");

        MvcResult result = mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(title, RESIDENTIAL_CATEGORY_ID,
                                fixture.residentialSubtype().getId(),
                                fixture.agent().getId(), fixture.amenity().getId(),
                                unique("Locality"), unique("City"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/properties/")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.slug").isNotEmpty())
                .andExpect(jsonPath("$.data.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAgent.id").value(fixture.agent().getId().toString()))
                .andExpect(jsonPath("$.data.amenities[0].name").value(fixture.amenity().getName()))
                .andExpect(jsonPath("$.data.primaryAgent.email").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAgent.phone").doesNotExist())
                .andReturn();

        UUID propertyId = readId(result);
        createdPropertyIds.add(propertyId);
        Property stored = propertyRepository.findById(propertyId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.DRAFT);
        assertThat(stored.getSlug()).isNotBlank();
        assertThat(stored.getListingType()).isEqualTo(ListingType.SALE);
        assertThat(stored.getCreatedBy().getId()).isEqualTo(fixture.admin().getId());
        assertThat(stored.getUpdatedBy()).isNull();
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getArchivedAt()).isNull();
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_CREATE, propertyId)).isEqualTo(1);
    }

    @Test
    void slugCollisionsAreBoundedAndDeterministic() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String title = unique("Duplicate Title");
        String body = createJson(title, RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));

        UUID firstId = createPropertyWithBody(admin, body);
        UUID secondId = createPropertyWithBody(admin, body);

        String firstSlug = propertyRepository.findById(firstId).orElseThrow().getSlug();
        String secondSlug = propertyRepository.findById(secondId).orElseThrow().getSlug();
        assertThat(firstSlug).isNotEqualTo(secondSlug);
        assertThat(secondSlug).isEqualTo(firstSlug + "-1");
    }

    @Test
    void nonAdminCannotCreateProperty() throws Exception {
        Fixture fixture = seedFixture();
        Session buyer = login(fixture.buyer().getEmail());
        Session agent = login(fixture.agent().getEmail());
        String body = createJson(unique("Denied"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(buyer.cookie())
                        .header(CSRF_HEADER, buyer.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void createRejectsUnknownNestedAndCrossCategoryFields() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String body = createJson(unique("Unknown"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));

        String withRootUnknown = body.replace("\"title\": \"",
                "\"status\": \"PUBLISHED\",\n  \"title\": \"");
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withRootUnknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));

        String withNestedUnknown = body.replace("\"locality\": \"",
                "\"secret\": true,\n    \"locality\": \"");
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withNestedUnknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("secret")));

        String crossCategory = createJson(unique("Mismatch"), RESIDENTIAL_CATEGORY_ID,
                fixture.commercialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossCategory))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("subtypeId")));
    }

    @Test
    void createRejectsIneligibleAgentAndInvalidValueRules() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String valid = createJson(unique("Rules"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));

        String ineligibleAgent = valid.replace(fixture.agent().getId().toString(),
                fixture.buyer().getId().toString());
        assertCreateValidationError(admin, ineligibleAgent, "primaryAgentId");
        assertCreateValidationError(admin,
                valid.replace("\"currencyCode\": \"INR\"", "\"currencyCode\": \"inr\""),
                "currencyCode");
        assertCreateValidationError(admin,
                valid.replace("\"currencyCode\": \"INR\"", "\"currencyCode\": \"XAU\""),
                "currencyCode");
        assertCreateValidationError(admin,
                valid.replace("\"latitude\": 22.3", "\"latitude\": 91.0"),
                "location.latitude");
        assertCreateValidationError(admin,
                valid.replace("\"areaUnit\": \"SQ_FT\"", "\"areaUnit\": \"\""),
                "areaUnit");
    }

    @Test
    void publicDetailReturnsPublishedPropertyWithSafeAgentProjection() throws Exception {
        Fixture fixture = seedFixture();
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("2500000.00"), Instant.now().minusSeconds(120),
                (short) 2, true, fixture.amenity());

        mockMvc.perform(get("/api/v1/properties/{id}", property.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(property.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.primaryImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.residentialDetails.bedrooms").value(2))
                .andExpect(jsonPath("$.data.amenities[0].id")
                        .value(fixture.amenity().getId().toString()))
                .andExpect(jsonPath("$.data.primaryAgent.id")
                        .value(fixture.agent().getId().toString()))
                .andExpect(jsonPath("$.data.primaryAgent.firstName").exists())
                .andExpect(jsonPath("$.data.primaryAgent.email").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAgent.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void publicAndBuyerDetailHidesNonPublishedProperty() throws Exception {
        Fixture fixture = seedFixture();
        Session buyer = login(fixture.buyer().getEmail());
        Property draft = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000000.00"), null, (short) 1, false, null);

        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId())
                        .cookie(buyer.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void publicDetailHidesEveryNonPublishedStatus() throws Exception {
        Fixture fixture = seedFixture();
        PropertyStatus[] hiddenStatuses = {
                PropertyStatus.DRAFT,
                PropertyStatus.UNAVAILABLE,
                PropertyStatus.SOLD,
                PropertyStatus.RENTED,
                PropertyStatus.ARCHIVED
        };

        for (PropertyStatus status : hiddenStatuses) {
            Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                    fixture.residentialSubtype(), status, unique("City"),
                    unique("Locality"), new BigDecimal("1000000.00"), null, (short) 1, false, null);

            mockMvc.perform(get("/api/v1/properties/{id}", property.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }

    @Test
    void assignedAgentAndAdminCanReadDraftButOtherAgentCannot() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());
        Property draft = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000000.00"), null, (short) 1, false, null);

        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()).cookie(agent.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()).cookie(admin.cookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId())
                        .cookie(otherAgent.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void searchFiltersPublishedPropertiesBeforePagination() throws Exception {
        Fixture fixture = seedFixture();
        String city = unique("City");
        String locality = unique("Locality");
        for (int i = 0; i < 3; i++) {
            persistProperty(fixture, fixture.agent(), residentialCategory(),
                    fixture.residentialSubtype(), PropertyStatus.PUBLISHED, city, locality,
                    BigDecimal.valueOf(1000000L + i), Instant.now().minusSeconds(60L - i),
                    (short) 2, false, null);
        }
        persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, city, locality,
                new BigDecimal("999000.00"), null, (short) 2, false, null);

        JsonNode firstPage = searchPayload("?city=" + city + "&page=0&size=1");
        assertThat(firstPage.at("/data").size()).isEqualTo(1);
        assertThat(firstPage.at("/meta/totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.at("/meta/totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.at("/meta/hasNext").asBoolean()).isTrue();
        assertThat(firstPage.at("/meta/hasPrevious").asBoolean()).isFalse();
        assertThat(firstPage.at("/data/0/status").asText()).isEqualTo("PUBLISHED");

        JsonNode secondPage = searchPayload("?city=" + city + "&page=1&size=1");
        assertThat(secondPage.at("/data").size()).isEqualTo(1);
        assertThat(secondPage.at("/meta/hasPrevious").asBoolean()).isTrue();
        assertThat(secondPage.at("/data/0/status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void searchAppliesFiltersSortingAndRejectsInvalidParameters() throws Exception {
        Fixture fixture = seedFixture();
        String city = unique("City");
        Property cheap = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, city, unique("Locality"),
                new BigDecimal("1000.00"), Instant.now(), (short) 2, false, null);
        Property middle = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, city, unique("Locality"),
                new BigDecimal("2000.00"), Instant.now(), (short) 2, false, fixture.amenity());
        persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, city, unique("Locality"),
                new BigDecimal("3000.00"), Instant.now(), (short) 2, false, null);

        String filter = "?city=" + city
                + "&categoryId=" + RESIDENTIAL_CATEGORY_ID
                + "&subtypeId=" + fixture.residentialSubtype().getId()
                + "&listingType=SALE&minPrice=1500&maxPrice=2500&minBedrooms=2"
                + "&amenityIds=" + fixture.amenity().getId();
        JsonNode filtered = search(filter);
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.at("/0/id").asText()).isEqualTo(middle.getId().toString());

        JsonNode sorted = search("?city=" + city + "&sort=price_asc&size=10");
        assertThat(sorted.size()).isEqualTo(3);
        assertThat(sorted.at("/0/id").asText()).isEqualTo(cheap.getId().toString());
        assertThat(sorted.at("/0/price").decimalValue()).isLessThan(
                sorted.at("/1/price").decimalValue());

        mockMvc.perform(get("/api/v1/properties").param("sort", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("sort")));
        mockMvc.perform(get("/api/v1/properties").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("size")));
        mockMvc.perform(get("/api/v1/properties").param("minBedrooms", "32768"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("minBedrooms")));
    }

    @Test
    void recentReturnsOnlyPublishedOrderedByPublishedAt() throws Exception {
        Fixture fixture = seedFixture();
        Property older = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"),
                Instant.now().minusSeconds(3600), (short) 2, false, null);
        Property newer = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("2000.00"),
                Instant.now().minusSeconds(60), (short) 2, false, null);
        Property draft = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("3000.00"), Instant.now(),
                (short) 2, false, null);

        JsonNode response = readData(mockMvc.perform(get("/api/v1/properties/recent")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn());
        List<String> ids = new ArrayList<>();
        response.forEach(node -> ids.add(node.at("/id").asText()));
        assertThat(ids).contains(newer.getId().toString(), older.getId().toString());
        assertThat(ids.indexOf(newer.getId().toString()))
                .isLessThan(ids.indexOf(older.getId().toString()));
        assertThat(ids).doesNotContain(draft.getId().toString());
    }

    @Test
    void relatedRequiresPublishedSourceAndExcludesHiddenCandidates() throws Exception {
        Fixture fixture = seedFixture();
        Property source = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), Instant.now(),
                (short) 2, false, null);
        Property related = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, source.getCity(),
                source.getLocality(), new BigDecimal("1100.00"), Instant.now(),
                (short) 2, false, null);
        Property hidden = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, source.getCity(),
                source.getLocality(), new BigDecimal("1200.00"), null,
                (short) 2, false, null);
        Property unrelated = persistProperty(fixture, fixture.agent(), commercialCategory(),
                fixture.commercialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("1300.00"), Instant.now(),
                null, false, null);

        JsonNode response = readData(mockMvc.perform(
                        get("/api/v1/properties/{id}/related", source.getId())
                                .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn());
        String payload = response.toString();
        assertThat(payload).contains(related.getId().toString());
        assertThat(payload).doesNotContain(hidden.getId().toString());
        assertThat(payload).doesNotContain(unrelated.getId().toString());
        assertThat(payload).doesNotContain(source.getId().toString());

        mockMvc.perform(get("/api/v1/properties/{id}/related", hidden.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void agentListIsScopedToAuthenticatedAgent() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());
        Session admin = login(fixture.admin().getEmail());
        Property ownDraft = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);
        Property ownPublished = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("2000.00"), Instant.now(),
                (short) 2, false, null);
        Property foreign = persistProperty(fixture, fixture.otherAgent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("3000.00"), null, (short) 3, false, null);

        JsonNode all = readData(mockMvc.perform(get("/api/v1/agents/me/properties")
                        .cookie(agent.cookie()).param("size", "10"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.toString())
                .contains(ownDraft.getId().toString())
                .contains(ownPublished.getId().toString())
                .doesNotContain(foreign.getId().toString());

        JsonNode drafts = readData(mockMvc.perform(get("/api/v1/agents/me/properties")
                        .cookie(agent.cookie())
                        .param("status", "DRAFT")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(drafts.size()).isEqualTo(1);
        assertThat(drafts.at("/0/id").asText()).isEqualTo(ownDraft.getId().toString());

        mockMvc.perform(get("/api/v1/agents/me/properties").cookie(otherAgent.cookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/agents/me/properties").cookie(admin.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminListIncludesAllStatusesAndAppliesFilters() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());
        String city = unique("City");
        Property archived = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.ARCHIVED, city, unique("Locality"),
                new BigDecimal("1000.00"), null, (short) 1, false, null);
        persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, city, unique("Locality"),
                new BigDecimal("2000.00"), null, (short) 2, false, null);

        JsonNode response = readData(mockMvc.perform(get("/api/v1/admin/properties")
                        .cookie(admin.cookie())
                        .param("status", "ARCHIVED")
                        .param("city", city)
                        .param("categoryId", RESIDENTIAL_CATEGORY_ID.toString())
                        .param("primaryAgentId", fixture.agent().getId().toString())
                        .param("keyword", archived.getTitle())
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.at("/0/id").asText()).isEqualTo(archived.getId().toString());
        assertThat(response.at("/0/status").asText()).isEqualTo("ARCHIVED");

        JsonNode all = readData(mockMvc.perform(get("/api/v1/admin/properties")
                        .cookie(admin.cookie())
                        .param("city", city)
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(all.size()).isEqualTo(2);

        mockMvc.perform(get("/api/v1/admin/properties").cookie(agent.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void assignedAgentCanPatchPartialFields() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);
        String originalDescription = property.getDescription();
        String newTitle = unique("Patched Title");

        mockMvc.perform(patch("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(newTitle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(newTitle))
                .andExpect(jsonPath("$.data.description").value(originalDescription))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        Property updated = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo(newTitle);
        assertThat(updated.getUpdatedBy().getId()).isEqualTo(fixture.agent().getId());
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_UPDATE, property.getId()))
                .isEqualTo(1);
    }

    @Test
    void buyerAndUnassignedAgentCannotPatch() throws Exception {
        Fixture fixture = seedFixture();
        Session buyer = login(fixture.buyer().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);
        String originalTitle = property.getTitle();

        mockMvc.perform(patch("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(buyer.cookie())
                        .header(CSRF_HEADER, buyer.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("Buyer Denied"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(otherAgent.cookie())
                        .header(CSRF_HEADER, otherAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("Other Agent Denied"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getTitle())
                .isEqualTo(originalTitle);
    }

    @Test
    void putRequiresFullReplacementFields() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);

        mockMvc.perform(put("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("Incomplete"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("description")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("listingType")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("price")))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("currencyCode")));

        String newTitle = unique("Replaced Title");
        mockMvc.perform(put("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullUpdateJson(newTitle, fixture.amenity().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(newTitle))
                .andExpect(jsonPath("$.data.listingType").value("RENT"))
                .andExpect(jsonPath("$.data.price").value(4500.00));

        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_UPDATE, property.getId()))
                .isEqualTo(1);
    }

    @Test
    void updateRejectsArchivedAndWrongCategoryDetails() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());
        Property archived = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.ARCHIVED, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);

        mockMvc.perform(patch("/api/v1/properties/{id}", archived.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("Archived Denied"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        Property draft = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("2000.00"), null, (short) 2, false, null);
        String wrongDetails = """
                {"commercialDetails":{"parkingSpaces":1}}
                """;
        mockMvc.perform(patch("/api/v1/properties/{id}", draft.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongDetails))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(hasItem("commercialDetails")));

        String forbiddenStatus = """
                {"status":"PUBLISHED"}
                """;
        mockMvc.perform(patch("/api/v1/properties/{id}", draft.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forbiddenStatus))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("status")));

        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_UPDATE, draft.getId()))
                .isZero();
    }

    @Test
    void adminArchivesLogicallyAndPreservesHistory() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), Instant.now(),
                (short) 2, true, fixture.amenity());

        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.id").value(property.getId().toString()));

        Property archived = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(PropertyStatus.ARCHIVED);
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getUpdatedBy().getId()).isEqualTo(fixture.admin().getId());
        assertThat(imageRepository.findByProperty_IdOrderBySortOrderAsc(property.getId()))
                .hasSize(1);
        assertThat(propertyAmenityRepository.findByProperty_Id(property.getId())).hasSize(1);
        assertThat(countAudit(AuditEventService.ACTION_PROPERTY_ARCHIVE, property.getId()))
                .isEqualTo(1);

        mockMvc.perform(get("/api/v1/properties/{id}", property.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void nonAdminCannotArchive() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Session buyer = login(fixture.buyer().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.PUBLISHED, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), Instant.now(),
                (short) 2, false, null);

        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(agent.cookie())
                        .header(CSRF_HEADER, agent.csrfToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(buyer.cookie())
                        .header(CSRF_HEADER, buyer.csrfToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(propertyRepository.findById(property.getId()).orElseThrow().getStatus())
                .isEqualTo(PropertyStatus.PUBLISHED);
    }

    @Test
    void mutationsRequireSessionAndCsrf() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Property property = persistProperty(fixture, fixture.agent(), residentialCategory(),
                fixture.residentialSubtype(), PropertyStatus.DRAFT, unique("City"),
                unique("Locality"), new BigDecimal("1000.00"), null, (short) 1, false, null);
        String createBody = createJson(unique("No Csrf"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                fixture.amenity().getId(), unique("Locality"), unique("City"));

        // CSRF runs before authorization, so a tokenless mutation is rejected
        // before the anonymous authentication entry point can return 401.
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("No Session"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/properties/{id}", property.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void errorEnvelopesAndUnmappedRoutesAreConsistent() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(get("/api/v1/properties/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/properties/not-a-uuid"))
                .andExpect(jsonPath("$.timestamp").exists());

        mockMvc.perform(get("/api/v1/properties/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/properties/featured"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/properties/featured").cookie(admin.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        // Stage 4.4: the category lookup is public and read-only.
        mockMvc.perform(get("/api/v1/property-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta").doesNotExist());
        // Stage 4.6: the status route is mapped, so a missing property is a 404.
        mockMvc.perform(patch("/api/v1/properties/{id}/status", UUID.randomUUID())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // Stage 4.5: image metadata reads are a public route; a nonexistent
        // property now resolves to a generic 404, not a denied deferred route.
        mockMvc.perform(get("/api/v1/properties/{id}/images", UUID.randomUUID())
                        .cookie(admin.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // Fixture persistence
    // ------------------------------------------------------------------

    private Fixture seedFixture() {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User otherAgent = persistUser("AGENT", "other-agent");
        User buyer = persistUser("BUYER", "buyer");
        PropertySubtype residentialSubtype =
                persistSubtype(residentialCategory(), "Residential Flat");
        PropertySubtype commercialSubtype =
                persistSubtype(commercialCategory(), "Office Space");
        Amenity amenity = persistAmenity();
        return new Fixture(admin, agent, otherAgent, buyer,
                residentialSubtype, commercialSubtype, amenity);
    }

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage42");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
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

    private Amenity persistAmenity() {
        Amenity amenity = new Amenity();
        amenity.setName(unique("Amenity"));
        amenity.setDescription("Stage 4.2 integration amenity");
        amenityRepository.saveAndFlush(amenity);
        createdAmenityIds.add(amenity.getId());
        return amenity;
    }

    private PropertyCategory residentialCategory() {
        return categoryRepository.findById(RESIDENTIAL_CATEGORY_ID).orElseThrow();
    }

    private PropertyCategory commercialCategory() {
        return categoryRepository.findById(COMMERCIAL_CATEGORY_ID).orElseThrow();
    }

    private Property persistProperty(
            Fixture fixture, User primaryAgent, PropertyCategory category,
            PropertySubtype subtype, PropertyStatus status, String city, String locality,
            BigDecimal price, Instant publishedAt, Short bedrooms,
            boolean primaryImage, Amenity amenity) {
        Property property = new Property();
        property.setTitle(unique("Persisted Listing"));
        property.setSlug(unique("persisted-listing"));
        property.setDescription("Stage 4.2 integration fixture description");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(price);
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Integration Road");
        property.setLocality(locality);
        property.setCity(city);
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(new BigDecimal("73.2000000"));
        property.setStatus(status);
        property.setPrimaryAgent(primaryAgent);
        property.setCreatedBy(fixture.admin());
        property.setPublishedAt(publishedAt);
        property.setArchivedAt(status == PropertyStatus.ARCHIVED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());

        if ("RESIDENTIAL".equals(category.getName())) {
            PropertyResidentialDetails details = new PropertyResidentialDetails();
            details.setProperty(property);
            details.setBedrooms(bedrooms == null ? 2 : bedrooms);
            details.setBathrooms((short) 2);
            details.setBalconies((short) 1);
            details.setFloorNumber((short) 3);
            details.setTotalFloors((short) 10);
            details.setParkingSpaces((short) 1);
            details.setFurnishingStatus("FURNISHED");
            residentialDetailsRepository.saveAndFlush(details);
        } else {
            PropertyCommercialDetails details = new PropertyCommercialDetails();
            details.setProperty(property);
            details.setParkingSpaces((short) 2);
            details.setFloorNumber((short) 1);
            details.setTotalFloors((short) 5);
            details.setWashrooms((short) 2);
            details.setPowerBackup(true);
            details.setSuitableFor("IT office");
            commercialDetailsRepository.saveAndFlush(details);
        }

        if (primaryImage) {
            PropertyImage image = new PropertyImage();
            image.setProperty(property);
            image.setStorageProvider("TEST");
            image.setStoragePublicId(property.getId().toString());
            image.setImageUrl("https://cdn.example.test/" + property.getId() + ".jpg");
            image.setAltText("Integration image");
            image.setSortOrder(0);
            image.setIsPrimary(true);
            imageRepository.saveAndFlush(image);
        }
        if (amenity != null) {
            PropertyAmenity link = new PropertyAmenity();
            link.setId(new PropertyAmenityId(property.getId(), amenity.getId()));
            link.setProperty(property);
            link.setAmenity(amenity);
            propertyAmenityRepository.saveAndFlush(link);
        }
        return property;
    }

    // ------------------------------------------------------------------
    // HTTP and JSON helpers
    // ------------------------------------------------------------------

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

    private UUID createPropertyWithBody(Session admin, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        UUID propertyId = readId(result);
        createdPropertyIds.add(propertyId);
        return propertyId;
    }

    private void assertCreateValidationError(Session admin, String body, String field)
            throws Exception {
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem(field)));
    }

    private JsonNode search(String query) throws Exception {
        return readData(mockMvc.perform(get("/api/v1/properties?" + query.substring(1)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode searchPayload(String query) throws Exception {
        return readPayload(mockMvc.perform(get("/api/v1/properties?" + query.substring(1)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode readPayload(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return readPayload(result).at("/data");
    }

    private UUID readId(MvcResult result) throws Exception {
        String id = readData(result).at("/id").asText();
        assertThat(id).isNotBlank();
        return UUID.fromString(id);
    }

    private long countAudit(String action, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, propertyId);
        return count == null ? 0L : count;
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

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private static String createJson(
            String title, UUID categoryId, UUID subtypeId, UUID agentId,
            UUID amenityId, String locality, String city) {
        return """
                {
                  "title": "%s",
                  "description": "Stage 4.2 create integration request",
                  "categoryId": "%s",
                  "subtypeId": "%s",
                  "listingType": "SALE",
                  "primaryAgentId": "%s",
                  "price": 2500000.00,
                  "currencyCode": "INR",
                  "areaValue": 1200.00,
                  "areaUnit": "SQ_FT",
                  "location": {
                    "addressLine": "1 Test Road",
                    "locality": "%s",
                    "city": "%s",
                    "state": "Gujarat",
                    "country": "India",
                    "postalCode": "390001",
                    "latitude": 22.3,
                    "longitude": 73.2
                  },
                  "residentialDetails": {
                    "bedrooms": 2,
                    "bathrooms": 2,
                    "balconies": 1,
                    "floorNumber": 3,
                    "totalFloors": 10,
                    "parkingSpaces": 1,
                    "furnishingStatus": "FURNISHED"
                  },
                  "amenityIds": ["%s"]
                }
                """.formatted(title, categoryId, subtypeId, agentId, locality, city, amenityId);
    }

    private static String patchJson(String title) {
        return """
                {"title":"%s"}
                """.formatted(title);
    }

    private static String fullUpdateJson(String title, UUID amenityId) {
        return """
                {
                  "title": "%s",
                  "description": "Stage 4.2 full replacement request",
                  "listingType": "RENT",
                  "price": 4500.00,
                  "currencyCode": "INR",
                  "areaValue": 900.00,
                  "areaUnit": "SQ_FT",
                  "location": {
                    "addressLine": "2 Replacement Road",
                    "locality": "Replacement Locality",
                    "city": "Vadodara",
                    "state": "Gujarat",
                    "country": "India",
                    "postalCode": "390001"
                  },
                  "residentialDetails": {
                    "bedrooms": 3,
                    "bathrooms": 2,
                    "balconies": 2
                  },
                  "amenityIds": ["%s"]
                }
                """.formatted(title, amenityId);
    }

    private record Session(Cookie cookie, String csrfToken) {
    }

    private record Fixture(
            User admin,
            User agent,
            User otherAgent,
            User buyer,
            PropertySubtype residentialSubtype,
            PropertySubtype commercialSubtype,
            Amenity amenity) {
    }
}
