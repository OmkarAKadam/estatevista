package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.AmenityRepository;
import com.estatevista.property.repository.PropertyAmenityRepository;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyCommercialDetailsRepository;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real-PostgreSQL integration verification for the Stage 4.4 specification,
 * amenity, and public reference-data slice.
 *
 * <p>Every authenticated request uses a real server-side session and the
 * server-issued CSRF token. No H2 database, Testcontainers, or mock
 * authentication is used.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PropertySpecificationsAndAmenitiesIntegrationTest {

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
    private AmenityRepository amenityRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;

    @Autowired
    private PropertyCommercialDetailsRepository commercialDetailsRepository;

    @Autowired
    private PropertyAmenityRepository propertyAmenityRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdCategoryIds = new ArrayList<>();
    private final List<UUID> createdSubtypeIds = new ArrayList<>();
    private final List<UUID> createdAmenityIds = new ArrayList<>();
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
        for (UUID categoryId : createdCategoryIds) {
            jdbcTemplate.update("DELETE FROM property_categories WHERE id = ?", categoryId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    @Test
    void residentialSpecificationCreationPersistsMatchingDetailsOnly() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String body = createJson(unique("Residential Listing"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                residentialDetails(3, 2), List.of(fixture.amenityOne().getId()));

        JsonNode data = readData(mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());

        UUID propertyId = UUID.fromString(data.at("/id").asText());
        assertThat(data.at("/category").asText()).isEqualTo("RESIDENTIAL");
        assertThat(data.at("/residentialDetails/bedrooms").asInt()).isEqualTo(3);
        assertThat(data.at("/residentialDetails/furnishingStatus").asText())
                .isEqualTo("FURNISHED");
        assertThat(data.at("/commercialDetails").isNull()).isTrue();
        assertThat(data.at("/amenities").size()).isEqualTo(1);
        assertThat(countDetails("property_residential_details", propertyId)).isEqualTo(1L);
        assertThat(countDetails("property_commercial_details", propertyId)).isZero();
        assertThat(countAmenityLinks(propertyId)).isEqualTo(1L);
    }

    @Test
    void commercialSpecificationCreationPersistsMatchingDetailsOnly() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String body = createJson(unique("Commercial Listing"), COMMERCIAL_CATEGORY_ID,
                fixture.commercialSubtype().getId(), fixture.agent().getId(),
                commercialDetails(), List.of(fixture.amenityTwo().getId()));

        JsonNode data = readData(mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());

        UUID propertyId = UUID.fromString(data.at("/id").asText());
        assertThat(data.at("/category").asText()).isEqualTo("COMMERCIAL");
        assertThat(data.at("/commercialDetails/washrooms").asInt()).isEqualTo(2);
        assertThat(data.at("/commercialDetails/powerBackup").asBoolean()).isTrue();
        assertThat(data.at("/residentialDetails").isNull()).isTrue();
        assertThat(countDetails("property_commercial_details", propertyId)).isEqualTo(1L);
        assertThat(countDetails("property_residential_details", propertyId)).isZero();
    }

    @Test
    void wrongCategoryDetailPayloadsAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        assertCreateValidationError(admin, createJson(unique("Wrong Residential"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), commercialDetails(), null), "commercialDetails");
        assertCreateValidationError(admin, createJson(unique("Wrong Commercial"),
                COMMERCIAL_CATEGORY_ID, fixture.commercialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2), null), "residentialDetails");
    }

    @Test
    void categoryAndSubtypeCompatibilityIsEnforced() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        assertCreateValidationError(admin, createJson(unique("Mismatched Subtype"),
                RESIDENTIAL_CATEGORY_ID, fixture.commercialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2), null), "subtypeId");

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(unique("Missing Category"), UUID.randomUUID(),
                                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                                residentialDetails(2, 2), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void requiredCategorySpecificDetailsAreEnforced() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        assertCreateValidationError(admin, createJson(unique("No Residential Details"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), null, null), "residentialDetails");
        assertCreateValidationError(admin, createJson(unique("No Commercial Details"),
                COMMERCIAL_CATEGORY_ID, fixture.commercialSubtype().getId(),
                fixture.agent().getId(), null, null), "commercialDetails");
    }

    @Test
    void invalidSpecificationAndPriceValuesAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        assertCreateValidationError(admin, createJson(unique("Negative Bedrooms"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(-1, 2), null),
                "residentialDetails.bedrooms");

        String negativePrice = createJson(unique("Negative Price"), RESIDENTIAL_CATEGORY_ID,
                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                residentialDetails(2, 2), null)
                .replace("\"price\": 2500000.00", "\"price\": -1.00");
        assertCreateValidationError(admin, negativePrice, "price");
    }

    @Test
    void residentialSpecificationPatchUpdatesOnlySuppliedFields() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Residential Patch"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null,
                                "\"residentialDetails\": {\"bedrooms\": 4}", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.residentialDetails.bedrooms").value(4))
                .andExpect(jsonPath("$.data.residentialDetails.bathrooms").value(2))
                .andExpect(jsonPath("$.data.residentialDetails.furnishingStatus")
                        .value("FURNISHED"))
                .andExpect(jsonPath("$.data.amenities[0].id")
                        .value(fixture.amenityOne().getId().toString()));
    }

    @Test
    void commercialSpecificationPatchUpdatesOnlySuppliedFields() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Commercial Patch"),
                COMMERCIAL_CATEGORY_ID, fixture.commercialSubtype().getId(),
                fixture.agent().getId(), commercialDetails(), null));

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null,
                                "\"commercialDetails\": {\"washrooms\": 5}", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commercialDetails.washrooms").value(5))
                .andExpect(jsonPath("$.data.commercialDetails.parkingSpaces").value(2))
                .andExpect(jsonPath("$.data.commercialDetails.powerBackup").value(true));
    }

    @Test
    void patchAppliesOnlyPresentScalarFields() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Partial Patch"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2), null));
        String newTitle = unique("Patched Title");

        JsonNode data = readData(mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(newTitle, null, null)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(data.at("/title").asText()).isEqualTo(newTitle);
        assertThat(data.at("/description").asText())
                .isEqualTo("Stage 4.4 create integration request");
        assertThat(data.at("/price").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2500000.00"));
        assertThat(data.at("/location/city").asText()).isEqualTo("Vadodara");
    }

    @Test
    void putReplacesAllAllowedFieldsAndSpecificationValues() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Put Replace"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));
        String replacementTitle = unique("Replaced Title");

        JsonNode data = readData(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                                .cookie(admin.cookie())
                                .header(CSRF_HEADER, admin.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fullUpdateJson(replacementTitle,
                                        "\"residentialDetails\": {\"bedrooms\": 5,"
                                                + "\"bathrooms\": 3}",
                                        List.of(fixture.amenityTwo().getId()))))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(data.at("/title").asText()).isEqualTo(replacementTitle);
        assertThat(data.at("/listingType").asText()).isEqualTo("RENT");
        assertThat(data.at("/price").decimalValue())
                .isEqualByComparingTo(new BigDecimal("4500.00"));
        assertThat(data.at("/location/city").asText()).isEqualTo("Surat");
        assertThat(data.at("/residentialDetails/bedrooms").asInt()).isEqualTo(5);
        assertThat(data.at("/residentialDetails/balconies").isNull()).isTrue();
        assertThat(data.at("/amenities").size()).isEqualTo(1);
        assertThat(data.at("/amenities/0/id").asText())
                .isEqualTo(fixture.amenityTwo().getId().toString());
    }

    @Test
    void categoryChangeIsRejectedAndStaleDetailRowsAreRemoved() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Category Lock"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2), null));

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null, null, null)
                                .replace("{}", "{\"categoryId\":\""
                                        + COMMERCIAL_CATEGORY_ID + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        jdbcTemplate.update("INSERT INTO property_commercial_details "
                + "(property_id, parking_spaces) VALUES (?, ?)", propertyId, 1);
        assertThat(countDetails("property_commercial_details", propertyId)).isEqualTo(1L);

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(unique("Still Residential"), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("RESIDENTIAL"))
                .andExpect(jsonPath("$.data.commercialDetails").doesNotExist());

        assertThat(countDetails("property_residential_details", propertyId)).isEqualTo(1L);
        assertThat(countDetails("property_commercial_details", propertyId)).isZero();
    }

    @Test
    void exactlyOneMatchingDetailRowRemainsAfterUpdates() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Detail Invariant"),
                COMMERCIAL_CATEGORY_ID, fixture.commercialSubtype().getId(),
                fixture.agent().getId(), commercialDetails(), null));

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null,
                                "\"commercialDetails\": {\"parkingSpaces\": 4}", null)))
                .andExpect(status().isOk());

        assertThat(countDetails("property_residential_details", propertyId)).isZero();
        assertThat(countDetails("property_commercial_details", propertyId)).isEqualTo(1L);
    }

    @Test
    void amenitiesAreAssociatedOnCreation() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Amenity Create"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId(), fixture.amenityTwo().getId())));

        JsonNode amenities = readData(mockMvc.perform(get("/api/v1/properties/{id}", propertyId)
                        .cookie(admin.cookie()))
                .andExpect(status().isOk())
                .andReturn()).at("/amenities");

        assertThat(amenities.size()).isEqualTo(2);
        assertThat(toNames(amenities)).containsExactlyInAnyOrder(
                fixture.amenityOne().getName(), fixture.amenityTwo().getName());
        assertThat(countAmenityLinks(propertyId)).isEqualTo(2L);
    }

    @Test
    void amenityAssociationsAreReplacedByPatchAndPut() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Amenity Replace"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));

        JsonNode afterPatch = readData(mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null, null,
                                List.of(fixture.amenityTwo().getId(),
                                        fixture.amenityThree().getId()))))
                .andExpect(status().isOk())
                .andReturn()).at("/amenities");
        assertThat(toNames(afterPatch)).containsExactlyInAnyOrder(
                fixture.amenityTwo().getName(), fixture.amenityThree().getName());
        assertThat(countAmenityLinks(propertyId)).isEqualTo(2L);

        JsonNode afterPut = readData(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                                .cookie(admin.cookie())
                                .header(CSRF_HEADER, admin.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fullUpdateJson(unique("Amenity Put"),
                                        residentialDetails(3, 2),
                                        List.of(fixture.amenityOne().getId()))))
                .andExpect(status().isOk())
                .andReturn()).at("/amenities");
        assertThat(toNames(afterPut)).containsExactly(fixture.amenityOne().getName());
        assertThat(countAmenityLinks(propertyId)).isEqualTo(1L);
    }

    @Test
    void duplicateAmenityIdsAreDeduplicatedDeterministically() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        JsonNode amenities = readData(mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(unique("Duplicate Amenities"),
                                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                                fixture.agent().getId(), residentialDetails(2, 2),
                                List.of(fixture.amenityOne().getId(),
                                        fixture.amenityOne().getId(),
                                        fixture.amenityTwo().getId()))))
                .andExpect(status().isCreated())
                .andReturn()).at("/amenities");

        assertThat(amenities.size()).isEqualTo(2);
        UUID propertyId = UUID.fromString(readLastCreatedPropertyId(fixture.admin()));
        assertThat(countAmenityLinks(propertyId)).isEqualTo(2L);
    }

    @Test
    void unknownAmenityIdIsRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(unique("Unknown Amenity"), RESIDENTIAL_CATEGORY_ID,
                                fixture.residentialSubtype().getId(), fixture.agent().getId(),
                                residentialDetails(2, 2), List.of(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("amenityIds")));
    }

    @Test
    void amenityRelationshipCompositeKeyPreventsDuplicateRows() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Amenity Key"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO property_amenities (property_id, amenity_id) VALUES (?, ?)",
                propertyId, fixture.amenityOne().getId()))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(countAmenityLinks(propertyId)).isEqualTo(1L);
    }

    @Test
    void crossAgentAmenityUpdateIsDeniedWithoutChangingLinks() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Owned Amenities"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));
        Session otherAgent = login(fixture.otherAgent().getEmail());

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(otherAgent.cookie())
                        .header(CSRF_HEADER, otherAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null, null,
                                List.of(fixture.amenityTwo().getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(countAmenityLinks(propertyId)).isEqualTo(1L);
        Boolean linked = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM property_amenities "
                        + "WHERE property_id = ? AND amenity_id = ?)",
                Boolean.class, propertyId, fixture.amenityOne().getId());
        assertThat(linked).isTrue();
    }

    @Test
    void publicCategoryLookupReturnsOnlyApprovedFields() throws Exception {
        JsonNode payload = lookup("/api/v1/property-categories");
        assertThat(payload.at("/success").asBoolean()).isTrue();
        assertThat(payload.has("meta")).isFalse();
        JsonNode data = payload.at("/data");
        assertThat(data.isArray()).isTrue();
        assertThat(toNames(data)).contains("RESIDENTIAL", "COMMERCIAL");
        for (JsonNode item : data) {
            assertThat(item.has("id")).isTrue();
            assertThat(item.has("name")).isTrue();
            assertThat(item.has("categoryId")).isFalse();
            assertThat(item.has("description")).isFalse();
        }
    }

    @Test
    void publicSubtypeLookupFiltersByCategoryAndRejectsInvalidFilters() throws Exception {
        JsonNode all = lookup("/api/v1/property-subtypes").at("/data");
        assertThat(toNames(all)).contains("Apartment", "Villa", "Independent House",
                "Penthouse", "Residential Plot", "Office Space", "Retail Shop",
                "Showroom", "Warehouse", "Commercial Plot");

        JsonNode filtered = lookup("/api/v1/property-subtypes?categoryId="
                + RESIDENTIAL_CATEGORY_ID).at("/data");
        assertThat(filtered.size()).isGreaterThanOrEqualTo(5);
        for (JsonNode item : filtered) {
            assertThat(item.at("/categoryId").asText())
                    .isEqualTo(RESIDENTIAL_CATEGORY_ID.toString());
            assertThat(item.has("description")).isFalse();
        }

        mockMvc.perform(get("/api/v1/property-subtypes?categoryId=" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("categoryId")));
        mockMvc.perform(get("/api/v1/property-subtypes?categoryId=not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void publicAmenityLookupReturnsSeededValuesWithNoInternalFields() throws Exception {
        JsonNode payload = lookup("/api/v1/amenities");
        assertThat(payload.at("/success").asBoolean()).isTrue();
        assertThat(payload.has("meta")).isFalse();
        JsonNode data = payload.at("/data");
        assertThat(toNames(data)).contains("Swimming Pool", "Gym", "Parking", "Security",
                "Garden", "Lift", "Power Backup", "Clubhouse");
        for (JsonNode item : data) {
            assertThat(item.has("id")).isTrue();
            assertThat(item.has("name")).isTrue();
            assertThat(item.has("categoryId")).isFalse();
        }
    }

    @Test
    void emptyReferenceDataCollectionReturnsSuccessfulEmptyArray() throws Exception {
        PropertyCategory emptyCategory = new PropertyCategory();
        emptyCategory.setName(unique("EC"));
        categoryRepository.saveAndFlush(emptyCategory);
        createdCategoryIds.add(emptyCategory.getId());

        JsonNode payload = lookup("/api/v1/property-subtypes?categoryId="
                + emptyCategory.getId());
        assertThat(payload.at("/success").asBoolean()).isTrue();
        assertThat(payload.at("/data").isArray()).isTrue();
        assertThat(payload.at("/data").isEmpty()).isTrue();
        assertThat(payload.has("meta")).isFalse();
    }

    @Test
    void assignmentAndOwnershipRegressionPreservesSpecificationWrites() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID propertyId = createProperty(admin, createJson(unique("Assignment Regression"),
                RESIDENTIAL_CATEGORY_ID, fixture.residentialSubtype().getId(),
                fixture.agent().getId(), residentialDetails(2, 2),
                List.of(fixture.amenityOne().getId())));

        JsonNode reassigned = readData(mockMvc.perform(
                        patch("/api/v1/admin/properties/{id}/assignment", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                                .cookie(admin.cookie())
                                .header(CSRF_HEADER, admin.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"primaryAgentId\":\""
                                        + fixture.otherAgent().getId() + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(reassigned.at("/primaryAgent/id").asText())
                .isEqualTo(fixture.otherAgent().getId().toString());

        Session newAgent = login(fixture.otherAgent().getEmail());
        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(newAgent.cookie())
                        .header(CSRF_HEADER, newAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null,
                                "\"residentialDetails\": {\"bedrooms\": 6}",
                                List.of(fixture.amenityTwo().getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.residentialDetails.bedrooms").value(6))
                .andExpect(jsonPath("$.data.amenities[0].id")
                        .value(fixture.amenityTwo().getId().toString()));

        Session oldAgent = login(fixture.agent().getEmail());
        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(oldAgent.cookie())
                        .header(CSRF_HEADER, oldAgent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson(null, null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Property stored = propertyRepository.findById(propertyId).orElseThrow();
        assertThat(stored.getPrimaryAgent().getId()).isEqualTo(fixture.otherAgent().getId());
        assertThat(stored.getStatus()).isEqualTo(PropertyStatus.DRAFT);
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
                persistSubtype(commercialCategory(), "Commercial Office");
        Amenity amenityOne = persistAmenity("Swimming Pool Fixture");
        Amenity amenityTwo = persistAmenity("Gym Fixture");
        Amenity amenityThree = persistAmenity("Parking Fixture");
        return new Fixture(admin, agent, otherAgent, buyer, residentialSubtype,
                commercialSubtype, amenityOne, amenityTwo, amenityThree);
    }

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage44");
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

    private Amenity persistAmenity(String label) {
        Amenity amenity = new Amenity();
        amenity.setName(unique(label));
        amenity.setDescription("Stage 4.4 integration amenity");
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

    private UUID createProperty(Session admin, String body) throws Exception {
        JsonNode data = readData(mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
        UUID propertyId = UUID.fromString(data.at("/id").asText());
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

    private JsonNode lookup(String path) throws Exception {
        return readPayload(mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode readPayload(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return readPayload(result).at("/data");
    }

    private String readLastCreatedPropertyId(User admin) {
        return jdbcTemplate.queryForObject(
                "SELECT id::text FROM properties WHERE created_by = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, admin.getId());
    }

    private long countDetails(String table, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE property_id = ?",
                Long.class, propertyId);
        return count == null ? 0L : count;
    }

    private long countAmenityLinks(UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM property_amenities WHERE property_id = ?",
                Long.class, propertyId);
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

    private static List<String> toNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            names.add(item.at("/name").asText());
        }
        return names;
    }

    private static String uuidList(List<UUID> ids) {
        return ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String residentialDetails(int bedrooms, int bathrooms) {
        return """
                "residentialDetails": {
                  "bedrooms": %d,
                  "bathrooms": %d,
                  "balconies": 1,
                  "floorNumber": 3,
                  "totalFloors": 10,
                  "parkingSpaces": 1,
                  "furnishingStatus": "FURNISHED"
                }
                """.formatted(bedrooms, bathrooms);
    }

    private static String commercialDetails() {
        return """
                "commercialDetails": {
                  "parkingSpaces": 2,
                  "floorNumber": 1,
                  "totalFloors": 5,
                  "washrooms": 2,
                  "powerBackup": true,
                  "suitableFor": "IT office"
                }
                """;
    }

    private static String createJson(
            String title, UUID categoryId, UUID subtypeId, UUID agentId,
            String detailsJson, List<UUID> amenityIds) {
        String details = detailsJson == null ? "" : detailsJson + ",";
        String amenities = amenityIds == null ? "null" : uuidList(amenityIds);
        return """
                {
                  "title": "%s",
                  "description": "Stage 4.4 create integration request",
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
                    "locality": "Alkapuri",
                    "city": "Vadodara",
                    "state": "Gujarat",
                    "country": "India",
                    "postalCode": "390001",
                    "latitude": 22.3,
                    "longitude": 73.2
                  },
                  %s
                  "amenityIds": %s
                }
                """.formatted(title, categoryId, subtypeId, agentId, details, amenities);
    }

    private static String patchJson(
            String title, String detailsJson, List<UUID> amenityIds) {
        List<String> parts = new ArrayList<>();
        if (title != null) {
            parts.add("\"title\":\"" + title + "\"");
        }
        if (detailsJson != null) {
            parts.add(detailsJson);
        }
        if (amenityIds != null) {
            parts.add("\"amenityIds\":" + uuidList(amenityIds));
        }
        return "{" + String.join(",", parts) + "}";
    }

    private static String fullUpdateJson(
            String title, String detailsJson, List<UUID> amenityIds) {
        String details = detailsJson == null ? "" : detailsJson + ",";
        return """
                {
                  "title": "%s",
                  "description": "Stage 4.4 full replacement request",
                  "listingType": "RENT",
                  "price": 4500.00,
                  "currencyCode": "INR",
                  "areaValue": 900.00,
                  "areaUnit": "SQ_FT",
                  "location": {
                    "addressLine": "2 Replacement Road",
                    "locality": "Replacement Locality",
                    "city": "Surat",
                    "state": "Gujarat",
                    "country": "India",
                    "postalCode": "395001"
                  },
                  %s
                  "amenityIds": %s
                }
                """.formatted(title, details, uuidList(amenityIds));
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
            Amenity amenityOne,
            Amenity amenityTwo,
            Amenity amenityThree) {
    }
}
