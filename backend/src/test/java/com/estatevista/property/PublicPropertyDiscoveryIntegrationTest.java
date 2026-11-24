package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Stage 5.2 public property-discovery contract verification.
 *
 * <p>Uses the existing isolated PostgreSQL integration infrastructure, real
 * session cookies, and server-issued CSRF tokens. No H2 database, Testcontainers,
 * mock authentication, or standalone controller slice is used. The tests seed
 * only their own rows and remove them after each method.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PublicPropertyDiscoveryIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final String RUN_TOKEN = UUID.randomUUID().toString().substring(0, 8);
    private static final UUID RESIDENTIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID COMMERCIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID APARTMENT_SUBTYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002001");
    private static final UUID VILLA_SUBTYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002002");
    private static final UUID OFFICE_SUBTYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002006");
    private static final UUID POOL_AMENITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003001");
    private static final UUID GYM_AMENITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003002");
    private static final UUID PARKING_AMENITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003003");

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

    private final List<UUID> createdUserIds = new ArrayList<>();
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
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    // ------------------------------------------------------------------
    // Search and filtering
    // ------------------------------------------------------------------

    @Test
    void everyApprovedSearchFilterWorksIndividuallyAndCaseInsensitively() throws Exception {
        User agent = persistUser("AGENT", "agent");
        User creator = persistUser("ADMIN", "creator");
        String cityA = unique("Alpha City");
        String cityB = unique("Beta City");
        String localityA = unique("Alpha Locality");
        String localityB = unique("Beta Locality");
        Property villa = seedProperty(agent, creator, PropertyStatus.PUBLISHED, cityA, localityA,
                "Solar Villa", new BigDecimal("1000.00"), ListingType.SALE, VILLA_SUBTYPE_ID,
                true, (short) 2, Instant.now(), List.of(POOL_AMENITY_ID, GYM_AMENITY_ID), true, null);
        Property apartment = seedProperty(agent, creator, PropertyStatus.PUBLISHED, cityB, localityB,
                "Lunar Apartment", new BigDecimal("9000.00"), ListingType.RENT,
                APARTMENT_SUBTYPE_ID, true, (short) 4, Instant.now(),
                List.of(GYM_AMENITY_ID), false, null);
        Property office = seedProperty(agent, creator, PropertyStatus.PUBLISHED, cityA, localityB,
                "Solar Office", new BigDecimal("5000.00"), ListingType.SALE, OFFICE_SUBTYPE_ID,
                false, null, Instant.now(), List.of(), false, null);
        seedProperty(agent, creator, PropertyStatus.DRAFT, cityA, localityA,
                "Solar Draft", new BigDecimal("500.00"), ListingType.SALE, VILLA_SUBTYPE_ID,
                true, (short) 5, null, List.of(POOL_AMENITY_ID), false, null);

        assertIds(search(params("keyword", "solar")), villa.getId(), office.getId());
        assertIds(search(params("keyword", "LUNAR")), apartment.getId());
        assertIds(search(params("categoryId", RESIDENTIAL_CATEGORY_ID.toString())),
                villa.getId(), apartment.getId());
        assertIds(search(params("categoryId", COMMERCIAL_CATEGORY_ID.toString())), office.getId());
        assertIds(search(params("subtypeId", VILLA_SUBTYPE_ID.toString())), villa.getId());
        assertIds(search(params("listingType", "rent")), apartment.getId());
        assertIds(search(params("minPrice", "2000", "maxPrice", "6000")), office.getId());
        assertIds(search(params("city", cityA.toLowerCase(Locale.ROOT))),
                villa.getId(), office.getId());
        assertIds(search(params("locality", localityB.toUpperCase(Locale.ROOT))),
                apartment.getId(), office.getId());
        assertIds(search(params("minBedrooms", "3")), apartment.getId());
        assertIds(search(params("maxBedrooms", "2")), villa.getId());
        assertIds(search(params("amenityIds", GYM_AMENITY_ID.toString())),
                villa.getId(), apartment.getId());
        assertIds(search(params("amenityIds", POOL_AMENITY_ID.toString(),
                "amenityIds", GYM_AMENITY_ID.toString())), villa.getId());
        assertIds(search(params("amenityIds", PARKING_AMENITY_ID.toString())));
    }

    @Test
    void combinedFiltersSortingAndPaginationReturnAccurateMeta() throws Exception {
        User agent = persistUser("AGENT", "agent");
        User creator = persistUser("ADMIN", "creator");
        String city = unique("Combined City");
        String locality = unique("Combined Locality");
        for (int price = 1000; price <= 5000; price += 1000) {
            seedProperty(agent, creator, PropertyStatus.PUBLISHED, city, locality,
                    "Combined " + price, BigDecimal.valueOf(price), ListingType.SALE,
                    VILLA_SUBTYPE_ID, true, (short) 3, Instant.now(),
                    List.of(POOL_AMENITY_ID), false, null);
        }
        seedProperty(agent, creator, PropertyStatus.DRAFT, city, locality,
                "Combined hidden", new BigDecimal("2500"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 3, Instant.now(),
                List.of(POOL_AMENITY_ID), false, null);

        MultiValueMap<String, String> filters = params(
                "city", city,
                "categoryId", RESIDENTIAL_CATEGORY_ID.toString(),
                "subtypeId", VILLA_SUBTYPE_ID.toString(),
                "listingType", "SALE",
                "minPrice", "1500",
                "maxPrice", "5500",
                "minBedrooms", "2",
                "maxBedrooms", "4",
                "amenityIds", POOL_AMENITY_ID.toString(),
                "sort", "price_asc",
                "page", "1",
                "size", "2");
        JsonNode page = searchPayload(filters);
        assertThat(page.at("/success").asBoolean()).isTrue();
        assertThat(page.at("/data").size()).isEqualTo(2);
        assertThat(page.at("/data/0/price").decimalValue()).isEqualByComparingTo("4000");
        assertThat(page.at("/data/1/price").decimalValue()).isEqualByComparingTo("5000");
        assertMeta(page, 1, 2, 4, 2, false, true);

        JsonNode defaults = searchPayload(params("city", city));
        assertMeta(defaults, 0, 12, 5, 1, false, false);
        assertThat(defaults.at("/data").size()).isEqualTo(5);

        JsonNode outOfRange = searchPayload(params("city", city, "page", "9", "size", "2"));
        assertThat(outOfRange.at("/data").size()).isZero();
        assertMeta(outOfRange, 9, 2, 5, 3, false, true);
    }

    @Test
    void allFourSortsUseStableSecondaryOrderingAndDefaultToNewest() throws Exception {
        User agent = persistUser("AGENT", "agent");
        User creator = persistUser("ADMIN", "creator");
        String city = unique("Sort City");
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Property oldest = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                unique("Sort Locality"), "Sort 1000", new BigDecimal("1000"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(), false, base);
        Property tieFirst = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                unique("Sort Locality"), "Sort 3000 A", new BigDecimal("3000"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(), false,
                base.plusSeconds(10));
        Property middle = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                unique("Sort Locality"), "Sort 2000", new BigDecimal("2000"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(), false,
                base.plusSeconds(20));
        Property tieSecond = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                unique("Sort Locality"), "Sort 3000 B", new BigDecimal("3000"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(), false,
                base.plusSeconds(10));

        List<UUID> tieOrder = jdbcTemplate.queryForList(
                "SELECT id FROM properties WHERE id IN (?, ?) ORDER BY id ASC",
                UUID.class, tieFirst.getId(), tieSecond.getId());
        UUID lowerTieId = tieOrder.get(0);
        UUID upperTieId = tieOrder.get(1);

        assertIdsInOrder(search(params("city", city, "sort", "newest")),
                middle.getId(), upperTieId, lowerTieId, oldest.getId());
        assertIdsInOrder(search(params("city", city, "sort", "oldest")),
                oldest.getId(), lowerTieId, upperTieId, middle.getId());
        assertIdsInOrder(search(params("city", city, "sort", "price_asc")),
                oldest.getId(), middle.getId(), lowerTieId, upperTieId);
        assertIdsInOrder(search(params("city", city, "sort", "price_desc")),
                upperTieId, lowerTieId, middle.getId(), oldest.getId());
        assertIdsInOrder(search(params("city", city, "sort", "  ")),
                middle.getId(), upperTieId, lowerTieId, oldest.getId());

        mockMvc.perform(get("/api/v1/properties").param("sort", "NEWEST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("sort")));
    }

    @Test
    void invalidSearchInputUsesApprovedErrorEnvelopes() throws Exception {
        mockMvc.perform(get("/api/v1/properties").param("keyword", "a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("keyword")));

        MultiValueMap<String, String> tooManyAmenities = new LinkedMultiValueMap<>();
        for (int i = 0; i < 21; i++) {
            tooManyAmenities.add("amenityIds", UUID.randomUUID().toString());
        }
        mockMvc.perform(get("/api/v1/properties").params(tooManyAmenities))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("amenityIds")));

        MultiValueMap<String, String> twentyAmenities = new LinkedMultiValueMap<>();
        for (int i = 0; i < 20; i++) {
            twentyAmenities.add("amenityIds", UUID.randomUUID().toString());
        }
        mockMvc.perform(get("/api/v1/properties").params(twentyAmenities))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/properties").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("page")));
        mockMvc.perform(get("/api/v1/properties").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("size")));
        mockMvc.perform(get("/api/v1/properties").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("size")));
        mockMvc.perform(get("/api/v1/properties").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(100));
        mockMvc.perform(get("/api/v1/properties").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/properties").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/properties").param("listingType", "HOLIDAY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("listingType")));
        mockMvc.perform(get("/api/v1/properties")
                        .param("minPrice", "5000").param("maxPrice", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("minPrice")));
        mockMvc.perform(get("/api/v1/properties")
                        .param("minBedrooms", "5").param("maxBedrooms", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("minBedrooms")));
        mockMvc.perform(get("/api/v1/properties").param("minBedrooms", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("minBedrooms")));
        // Unknown query parameters are ignored consistently and cannot widen visibility.
        mockMvc.perform(get("/api/v1/properties").param("unknownFilter", "value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void sqlInjectionAttemptsRemainParameterizedAndCannotBypassVisibility() throws Exception {
        User agent = persistUser("AGENT", "agent");
        User creator = persistUser("ADMIN", "creator");
        String city = unique("Injection City");
        Property visible = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                unique("Injection Locality"), "Injection Villa", new BigDecimal("1000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(),
                false, null);
        Property hidden = seedProperty(agent, creator, PropertyStatus.DRAFT, city,
                unique("Injection Locality"), "Injection Hidden", new BigDecimal("2000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, null, List.of(), false, null);

        String hiddenId = hidden.getId().toString();
        String visibleId = visible.getId().toString();
        String attack = "' OR 1=1 --";
        assertThat(search(params("keyword", attack)).toString()).doesNotContain(hiddenId);
        assertThat(search(params("keyword", "%")).toString()).doesNotContain(hiddenId);
        assertThat(search(params("city", attack)).toString()).doesNotContain(hiddenId);
        assertThat(search(params("locality", attack)).toString()).doesNotContain(hiddenId);
        assertThat(search(params("city", city, "status", "PUBLISHED")).toString())
                .contains(visibleId).doesNotContain(hiddenId);

        mockMvc.perform(get("/api/v1/properties").param("listingType", attack))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/properties").param("amenityIds", attack))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    // ------------------------------------------------------------------
    // Visibility, detail, images, related, and recent
    // ------------------------------------------------------------------

    @Test
    void publicDiscoveryExcludesEveryNonPublishedStatus() throws Exception {
        User agent = persistUser("AGENT", "agent");
        User creator = persistUser("ADMIN", "creator");
        String city = unique("Visibility City");
        String locality = unique("Visibility Locality");
        Instant publishedAt = Instant.now().minusSeconds(120);
        Property published = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city, locality,
                "Visible Villa", new BigDecimal("1000"), ListingType.SALE, VILLA_SUBTYPE_ID,
                true, (short) 2, publishedAt, List.of(), true, null);
        Property relatedPublished = seedProperty(agent, creator, PropertyStatus.PUBLISHED, city,
                locality, "Visible Neighbour", new BigDecimal("1100"), ListingType.SALE,
                VILLA_SUBTYPE_ID, true, (short) 2, publishedAt, List.of(), false, null);
        List<PropertyStatus> hiddenStatuses = List.of(
                PropertyStatus.DRAFT, PropertyStatus.UNAVAILABLE, PropertyStatus.SOLD,
                PropertyStatus.RENTED, PropertyStatus.ARCHIVED);
        List<UUID> hiddenIds = new ArrayList<>();
        int counter = 0;
        for (PropertyStatus status : hiddenStatuses) {
            Property hidden = seedProperty(agent, creator, status, city, locality,
                    "Hidden " + status.name(), BigDecimal.valueOf(2000 + counter),
                    ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2,
                    publishedAt, List.of(), true, null);
            hiddenIds.add(hidden.getId());
            counter++;
        }

        JsonNode search = searchPayload(params("city", city, "size", "100"));
        assertThat(ids(search.at("/data"))).containsExactlyInAnyOrder(
                published.getId(), relatedPublished.getId());
        assertThat(search.at("/meta/totalElements").asLong()).isEqualTo(2);
        for (UUID hiddenId : hiddenIds) {
            assertThat(search.toString()).doesNotContain(hiddenId.toString());
        }

        JsonNode recent = getJson("/api/v1/properties/recent?size=100");
        assertThat(ids(recent.at("/data"))).contains(published.getId(), relatedPublished.getId());
        for (UUID hiddenId : hiddenIds) {
            assertThat(recent.toString()).doesNotContain(hiddenId.toString());
        }

        JsonNode related = getJson("/api/v1/properties/" + published.getId()
                + "/related?size=100");
        assertThat(ids(related.at("/data"))).containsExactly(relatedPublished.getId());

        for (UUID hiddenId : hiddenIds) {
            mockMvc.perform(get("/api/v1/properties/{id}", hiddenId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            mockMvc.perform(get("/api/v1/properties/{id}/images", hiddenId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            mockMvc.perform(get("/api/v1/properties/{id}/related", hiddenId))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void propertyDetailFollowsVisibilityRulesAndExposesNoPrivateFields() throws Exception {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User otherAgent = persistUser("AGENT", "other-agent");
        User buyer = persistUser("BUYER", "buyer");
        String city = unique("Detail City");
        Property published = seedProperty(agent, admin, PropertyStatus.PUBLISHED, city,
                unique("Detail Locality"), "Public Detail Villa", new BigDecimal("1000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(),
                true, null);
        Property draft = seedProperty(agent, admin, PropertyStatus.DRAFT, city,
                unique("Detail Locality"), "Private Detail Villa", new BigDecimal("2000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, null, List.of(), true, null);

        MvcResult result = mockMvc.perform(get("/api/v1/properties/{id}", published.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(published.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.primaryAgent.id").value(agent.getId().toString()))
                .andExpect(jsonPath("$.data.meta").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(agent.getEmail()).doesNotContain(agent.getPhone())
                .doesNotContain("createdBy").doesNotContain("updatedBy")
                .doesNotContain("storagePublicId").doesNotContain("passwordHash");

        Session buyerSession = login(buyer.getEmail());
        Session assignedSession = login(agent.getEmail());
        Session otherSession = login(otherAgent.getEmail());
        Session adminSession = login(admin.getEmail());
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()).cookie(buyerSession.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()).cookie(otherSession.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId())
                        .cookie(assignedSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/properties/{id}", draft.getId()).cookie(adminSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/properties/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void publicImagesFollowVisibilityAndHideProviderMetadata() throws Exception {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User buyer = persistUser("BUYER", "buyer");
        String city = unique("Image City");
        Property published = seedProperty(agent, admin, PropertyStatus.PUBLISHED, city,
                unique("Image Locality"), "Image Villa", new BigDecimal("1000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, Instant.now(), List.of(),
                true, null);
        Property draft = seedProperty(agent, admin, PropertyStatus.DRAFT, city,
                unique("Image Locality"), "Hidden Image Villa", new BigDecimal("2000"),
                ListingType.SALE, VILLA_SUBTYPE_ID, true, (short) 2, null, List.of(),
                true, null);

        MvcResult result = mockMvc.perform(
                        get("/api/v1/properties/{id}/images", published.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data[0].isPrimary").value(true))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("storagePublicId").doesNotContain("storageProvider")
                .doesNotContain("TEST");

        Session buyerSession = login(buyer.getEmail());
        Session agentSession = login(agent.getEmail());
        mockMvc.perform(get("/api/v1/properties/{id}/images", draft.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}/images", draft.getId())
                        .cookie(buyerSession.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}/images", draft.getId())
                        .cookie(agentSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void referenceEndpointsArePublicReadOnlyOrderedAndValidated() throws Exception {
        User buyer = persistUser("BUYER", "buyer");
        Session buyerSession = login(buyer.getEmail());

        JsonNode categories = getJson("/api/v1/property-categories");
        assertThat(categories.at("/success").asBoolean()).isTrue();
        assertThat(categories.at("/data").isArray()).isTrue();
        assertThat(categories.at("/meta").isMissingNode()).isTrue();
        assertThat(ids(categories.at("/data"))).contains(
                RESIDENTIAL_CATEGORY_ID, COMMERCIAL_CATEGORY_ID);
        assertThat(categories.at("/data/0").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "name");

        JsonNode subtypes = getJson("/api/v1/property-subtypes");
        assertThat(subtypes.at("/data").isArray()).isTrue();
        assertThat(subtypes.at("/meta").isMissingNode()).isTrue();
        assertThat(subtypes.at("/data/0").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "categoryId", "name");

        JsonNode amenities = getJson("/api/v1/amenities");
        assertThat(amenities.at("/data").isArray()).isTrue();
        assertThat(amenities.at("/meta").isMissingNode()).isTrue();
        assertThat(amenities.at("/data/0").properties()).extracting(java.util.Map.Entry::getKey)
                .contains("id", "name")
                .doesNotContain("categoryId");

        JsonNode filtered = getJson("/api/v1/property-subtypes?categoryId="
                + RESIDENTIAL_CATEGORY_ID);
        filtered.at("/data").forEach(node ->
                assertThat(node.at("/categoryId").asText())
                        .isEqualTo(RESIDENTIAL_CATEGORY_ID.toString()));

        mockMvc.perform(get("/api/v1/property-subtypes").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/property-subtypes")
                        .param("categoryId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("categoryId")));
        for (String path : List.of("/api/v1/property-categories", "/api/v1/property-subtypes",
                "/api/v1/amenities")) {
            mockMvc.perform(post(path).cookie(buyerSession.cookie())
                            .header(CSRF_HEADER, buyerSession.csrfToken()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(path).cookie(buyerSession.cookie())
                            .header(CSRF_HEADER, buyerSession.csrfToken()))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------
    // Featured restriction, schema, and management regression
    // ------------------------------------------------------------------

    @Test
    void featuredEndpointRemainsDeniedAndNoFeaturedSchemaExists() throws Exception {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User buyer = persistUser("BUYER", "buyer");
        Session adminSession = login(admin.getEmail());
        Session agentSession = login(agent.getEmail());
        Session buyerSession = login(buyer.getEmail());

        mockMvc.perform(get("/api/v1/properties/featured"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/properties/featured").cookie(buyerSession.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/properties/featured").cookie(agentSession.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/properties/featured").cookie(adminSession.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/properties/featured")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500").cookie(adminSession.cookie())
                        .header(CSRF_HEADER, adminSession.csrfToken()))
                .andExpect(status().isForbidden());

        Integer featuredColumns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'properties' AND column_name = 'is_featured'",
                Integer.class);
        assertThat(featuredColumns).isZero();
        Integer flywayVersion = jdbcTemplate.queryForObject(
                "SELECT max(cast(version AS integer)) FROM flyway_schema_history "
                        + "WHERE success = true", Integer.class);
        assertThat(flywayVersion).isEqualTo(17);
    }

    @Test
    void propertyManagementMutationsRemainSessionCsrfAndRoleProtected() throws Exception {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User otherAgent = persistUser("AGENT", "other-agent");
        User buyer = persistUser("BUYER", "buyer");
        Session adminSession = login(admin.getEmail());
        Session otherAgentSession = login(otherAgent.getEmail());
        Session buyerSession = login(buyer.getEmail());
        String body = createJson(unique("Regression Villa"), agent.getId(),
                unique("Regression Locality"), unique("Regression City"));

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500").cookie(adminSession.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500").cookie(buyerSession.cookie())
                        .header(CSRF_HEADER, buyerSession.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        MvcResult created = mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(adminSession.cookie())
                        .header(CSRF_HEADER, adminSession.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        UUID propertyId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString())
                        .at("/data/id").asText());
        createdPropertyIds.add(propertyId);

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(otherAgentSession.cookie())
                        .header(CSRF_HEADER, otherAgentSession.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Unassigned change\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/properties/{id}", propertyId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .cookie(adminSession.cookie())
                        .header(CSRF_HEADER, adminSession.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        assertThat(search(params("keyword", "Regression")).toString())
                .doesNotContain(propertyId.toString());
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage52");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private Property seedProperty(
            User agent, User creator, PropertyStatus status, String city, String locality,
            String title, BigDecimal price, ListingType listingType, UUID subtypeId,
            boolean residential, Short bedrooms, Instant publishedAt,
            List<UUID> amenityIds, boolean primaryImage, Instant createdAt) {
        PropertyCategory category = categoryRepository.findById(
                residential ? RESIDENTIAL_CATEGORY_ID : COMMERCIAL_CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypeRepository.findById(subtypeId).orElseThrow();
        Property property = new Property();
        property.setTitle(unique(title));
        property.setSlug(unique(slug(title)));
        property.setDescription("Stage 5.2 public discovery fixture description");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(listingType);
        property.setPrice(price);
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Discovery Road");
        property.setLocality(locality);
        property.setCity(city);
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(new BigDecimal("73.2000000"));
        property.setStatus(status);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(creator);
        property.setPublishedAt(publishedAt);
        property.setArchivedAt(status == PropertyStatus.ARCHIVED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());
        if (createdAt != null) {
            jdbcTemplate.update("UPDATE properties SET created_at = ?, updated_at = ? WHERE id = ?",
                    Timestamp.from(createdAt), Timestamp.from(createdAt), property.getId());
        }

        if (residential) {
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
            image.setAltText("Discovery fixture image");
            image.setSortOrder(0);
            image.setIsPrimary(true);
            imageRepository.saveAndFlush(image);
        }
        for (UUID amenityId : amenityIds) {
            // The amenity link is inserted with a native statement to keep the
            // fixture independent of repository flush ordering for composite IDs.
            jdbcTemplate.update(
                    "INSERT INTO property_amenities (property_id, amenity_id) VALUES (?, ?)",
                    property.getId(), amenityId);
        }
        return property;
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

    private JsonNode getJson(String url) throws Exception {
        return readPayload(mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
    }

    private JsonNode search(MultiValueMap<String, String> params) throws Exception {
        return searchPayload(params).at("/data");
    }

    private JsonNode searchPayload(MultiValueMap<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/properties");
        params.forEach((name, values) -> values.forEach(value -> request.param(name, value)));
        return readPayload(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
    }

    private JsonNode readPayload(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        createdSessionIds.add(cookie.getValue());
        return cookie;
    }

    private static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private static void assertMeta(JsonNode payload, int page, int size, long totalElements,
            int totalPages, boolean hasNext, boolean hasPrevious) {
        assertThat(payload.at("/meta/page").asInt()).isEqualTo(page);
        assertThat(payload.at("/meta/size").asInt()).isEqualTo(size);
        assertThat(payload.at("/meta/totalElements").asLong()).isEqualTo(totalElements);
        assertThat(payload.at("/meta/totalPages").asInt()).isEqualTo(totalPages);
        assertThat(payload.at("/meta/hasNext").asBoolean()).isEqualTo(hasNext);
        assertThat(payload.at("/meta/hasPrevious").asBoolean()).isEqualTo(hasPrevious);
        assertThat(payload.at("/meta").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "page", "size", "totalElements", "totalPages", "hasNext", "hasPrevious");
    }

    private static void assertIds(JsonNode data, UUID... expected) {
        assertThat(ids(data)).containsExactlyInAnyOrder(expected);
    }

    private static void assertIdsInOrder(JsonNode data, UUID... expected) {
        assertThat(ids(data)).containsExactly(expected);
    }

    private static List<UUID> ids(JsonNode data) {
        List<UUID> ids = new ArrayList<>();
        data.forEach(node -> ids.add(UUID.fromString(node.at("/id").asText())));
        return ids;
    }

    private static String unique(String label) {
        return label + "-" + RUN_TOKEN + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String slug(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String createJson(String title, UUID agentId, String locality, String city) {
        return """
                {
                  "title": "%s",
                  "description": "Stage 5.2 management regression request",
                  "categoryId": "%s",
                  "subtypeId": "%s",
                  "listingType": "SALE",
                  "primaryAgentId": "%s",
                  "price": 2500000.00,
                  "currencyCode": "INR",
                  "areaValue": 1200.00,
                  "areaUnit": "SQ_FT",
                  "location": {
                    "addressLine": "1 Discovery Road",
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
                """.formatted(title, RESIDENTIAL_CATEGORY_ID, VILLA_SUBTYPE_ID, agentId,
                locality, city, POOL_AMENITY_ID);
    }

    private record Session(Cookie cookie, String csrfToken) {
    }
}
