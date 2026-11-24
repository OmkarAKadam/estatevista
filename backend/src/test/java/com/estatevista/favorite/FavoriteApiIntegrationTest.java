package com.estatevista.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real-PostgreSQL, real-session verification of the approved Stage 6.2
 * favorites API. No mock authentication, H2, or standalone controller slice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class FavoriteApiIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final UUID CATEGORY_ID =
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
    private PropertyRepository propertyRepository;
    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;
    @Autowired
    private PropertyImageRepository imageRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        for (UUID propertyId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM favorites WHERE property_id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM favorites WHERE buyer_id = ?", userId);
        }
        for (UUID propertyId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM property_images WHERE property_id = ?", propertyId);
            jdbcTemplate.update(
                    "DELETE FROM property_residential_details WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    @Test
    void buyerCanAddListInspectAndRemoveFavoriteThroughRealSession() throws Exception {
        User admin = persistUser("ADMIN", "fav-api-admin");
        User agent = persistUser("AGENT", "fav-api-agent");
        User buyer = persistUser("BUYER", "fav-api-buyer");
        Property published = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Villa One");
        Property draft = persistProperty(admin, agent, PropertyStatus.DRAFT, "Villa Draft");
        Session session = login(buyer.getEmail());

        mockMvc.perform(post("/api/v1/favorites/" + published.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Property added to favorites"))
                .andExpect(jsonPath("$.data.propertyId").value(published.getId().toString()))
                .andExpect(jsonPath("$.data.isFavorite").value(true));
        assertThat(favoriteCount(buyer.getId(), published.getId())).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/favorites/" + published.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFavorite").value(true));
        assertThat(favoriteCount(buyer.getId(), published.getId())).isEqualTo(1L);

        mockMvc.perform(get("/api/v1/favorites").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].propertyId").value(published.getId().toString()))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].property.id").value(published.getId().toString()))
                .andExpect(jsonPath("$.data[0].property.title").value(published.getTitle()))
                .andExpect(jsonPath("$.data[0].property.category").value("RESIDENTIAL"))
                .andExpect(jsonPath("$.data[0].property.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].property.bedrooms").value(2))
                .andExpect(jsonPath("$.data[0].property.primaryImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(12))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));

        mockMvc.perform(get("/api/v1/favorites/" + published.getId() + "/status")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.propertyId").value(published.getId().toString()))
                .andExpect(jsonPath("$.data.isFavorite").value(true));

        String batchIds = published.getId() + "," + published.getId() + ","
                + draft.getId() + "," + UUID.randomUUID();
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", batchIds)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.favorites.length()").value(3))
                .andExpect(jsonPath("$.data.favorites[0].propertyId")
                        .value(published.getId().toString()))
                .andExpect(jsonPath("$.data.favorites[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.favorites[1].propertyId")
                        .value(draft.getId().toString()))
                .andExpect(jsonPath("$.data.favorites[1].isFavorite").value(false))
                .andExpect(jsonPath("$.data.favorites[2].isFavorite").value(false));

        mockMvc.perform(get("/api/v1/properties/" + published.getId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFavorite").doesNotExist())
                .andExpect(jsonPath("$.data.favorite").doesNotExist());

        assertPostFails(published.getId(), session, draft.getId(), 404, "NOT_FOUND");
        assertPostFails(published.getId(), session, UUID.randomUUID(), 404, "NOT_FOUND");

        mockMvc.perform(delete("/api/v1/favorites/" + published.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
        assertThat(favoriteCount(buyer.getId(), published.getId())).isZero();

        mockMvc.perform(delete("/api/v1/favorites/" + published.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void nonPublicLifecycleRetainsFavoriteWithRestrictedPlaceholderAndRestoresSummary()
            throws Exception {
        User admin = persistUser("ADMIN", "fav-life-admin");
        User agent = persistUser("AGENT", "fav-life-agent");
        User buyer = persistUser("BUYER", "fav-life-buyer");
        Property property = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Lifecycle Villa");
        Session session = login(buyer.getEmail());

        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isCreated());
        String originalCreatedAt = readCreatedAt(buyer.getId(), property.getId());

        setPropertyStatus(property.getId(), PropertyStatus.DRAFT);

        mockMvc.perform(get("/api/v1/favorites").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].propertyId").value(property.getId().toString()))
                .andExpect(jsonPath("$.data[0].property.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data[0].property.placeholder")
                        .value("Property no longer available"))
                .andExpect(jsonPath("$.data[0].property.id").doesNotExist())
                .andExpect(jsonPath("$.data[0].property.title").doesNotExist())
                .andExpect(jsonPath("$.data[0].property.price").doesNotExist())
                .andExpect(jsonPath("$.data[0].property.status").doesNotExist())
                .andExpect(jsonPath("$.data[0].property.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.data[0].property.description").doesNotExist());

        mockMvc.perform(get("/api/v1/favorites/" + property.getId() + "/status")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFavorite").value(false));
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", property.getId().toString())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorites[0].isFavorite").value(false));

        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(favoriteCount(buyer.getId(), property.getId())).isEqualTo(1L);

        setPropertyStatus(property.getId(), PropertyStatus.PUBLISHED);

        mockMvc.perform(get("/api/v1/favorites").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].property.id").value(property.getId().toString()))
                .andExpect(jsonPath("$.data[0].property.title").value(property.getTitle()))
                .andExpect(jsonPath("$.data[0].property.status").value("PUBLISHED"));
        assertThat(favoriteCount(buyer.getId(), property.getId())).isEqualTo(1L);
        assertThat(readCreatedAt(buyer.getId(), property.getId())).isEqualTo(originalCreatedAt);

        mockMvc.perform(delete("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().isNoContent());
        assertThat(favoriteCount(buyer.getId(), property.getId())).isZero();
    }

    @Test
    void favoritesArePrivateAndAllRoleChecksFailClosed() throws Exception {
        User admin = persistUser("ADMIN", "fav-sec-admin");
        User agent = persistUser("AGENT", "fav-sec-agent");
        User buyer = persistUser("BUYER", "fav-sec-buyer");
        User otherBuyer = persistUser("BUYER", "fav-sec-other-buyer");
        Property property = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Private Villa");
        Session buyerSession = login(buyer.getEmail());
        Session otherSession = login(otherBuyer.getEmail());
        Session agentSession = login(agent.getEmail());
        Session adminSession = login(admin.getEmail());

        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(buyerSession.cookie())
                        .header(CSRF_HEADER, buyerSession.csrfToken()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/favorites/" + property.getId() + "/status")
                        .cookie(otherSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFavorite").value(false));
        mockMvc.perform(get("/api/v1/favorites").cookie(otherSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(otherSession.cookie())
                        .header(CSRF_HEADER, otherSession.csrfToken()))
                .andExpect(status().isNoContent());
        assertThat(favoriteCount(buyer.getId(), property.getId())).isEqualTo(1L);

        for (Session denied : List.of(agentSession, adminSession)) {
            mockMvc.perform(get("/api/v1/favorites").cookie(denied.cookie()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
            mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .cookie(denied.cookie())
                            .header(CSRF_HEADER, denied.csrfToken()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/favorites/" + property.getId())
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .cookie(denied.cookie())
                            .header(CSRF_HEADER, denied.csrfToken()))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(get("/api/v1/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .cookie(buyerSession.cookie())
                        .header(CSRF_HEADER, buyerSession.csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .cookie(buyerSession.cookie())
                        .header(CSRF_HEADER, buyerSession.csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(buyerSession.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    void paginationOrderingAndBatchValidationAreEnforced() throws Exception {
        User admin = persistUser("ADMIN", "fav-page-admin");
        User agent = persistUser("AGENT", "fav-page-agent");
        User buyer = persistUser("BUYER", "fav-page-buyer");
        Property first = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Order First");
        Property second = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Order Second");
        Property third = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Order Third");
        Session session = login(buyer.getEmail());
        for (Property property : List.of(first, second, third)) {
            mockMvc.perform(post("/api/v1/favorites/" + property.getId())
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .cookie(session.cookie())
                            .header(CSRF_HEADER, session.csrfToken()))
                    .andExpect(status().isCreated());
        }
        setFavoriteCreatedAt(buyer.getId(), first.getId(), "2026-11-20T10:00:00Z");
        setFavoriteCreatedAt(buyer.getId(), second.getId(), "2026-11-21T10:00:00Z");
        setFavoriteCreatedAt(buyer.getId(), third.getId(), "2026-11-22T10:00:00Z");

        mockMvc.perform(get("/api/v1/favorites")
                        .param("sort", "newest").param("size", "2").param("page", "0")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].propertyId").value(third.getId().toString()))
                .andExpect(jsonPath("$.data[1].propertyId").value(second.getId().toString()))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true))
                .andExpect(jsonPath("$.meta.hasPrevious").value(false));

        mockMvc.perform(get("/api/v1/favorites")
                        .param("sort", "newest").param("size", "2").param("page", "1")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].propertyId").value(first.getId().toString()))
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.hasPrevious").value(true));

        mockMvc.perform(get("/api/v1/favorites")
                        .param("sort", "oldest").param("size", "3")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].propertyId").value(first.getId().toString()))
                .andExpect(jsonPath("$.data[1].propertyId").value(second.getId().toString()))
                .andExpect(jsonPath("$.data[2].propertyId").value(third.getId().toString()));

        mockMvc.perform(get("/api/v1/favorites").param("page", "-1").cookie(session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/favorites").param("size", "0").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/favorites").param("size", "101").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/favorites").param("sort", "price_asc").cookie(session.cookie()))
                .andExpect(status().isBadRequest());

        String canonical = first.getId().toString();
        mockMvc.perform(get("/api/v1/favorites/status").cookie(session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", "").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", "not-a-uuid").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", canonical.toUpperCase(Locale.ROOT))
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        String overCap = IntStream.range(0, 101)
                .mapToObj(index -> canonical)
                .collect(Collectors.joining(","));
        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", overCap).cookie(session.cookie()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/favorites/not-a-uuid/status").cookie(session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    private void assertPostFails(
            UUID buyerFavoriteId, Session session, UUID attemptedId,
            int expectedStatus, String expectedCode) throws Exception {
        assertThat(buyerFavoriteId).isNotNull();
        mockMvc.perform(post("/api/v1/favorites/" + attemptedId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(expectedCode));
    }

    private long favoriteCount(UUID buyerId, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE buyer_id = ? AND property_id = ?",
                Long.class, buyerId, propertyId);
        return count == null ? 0L : count;
    }

    private String readCreatedAt(UUID buyerId, UUID propertyId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT created_at::text FROM favorites WHERE buyer_id = ? AND property_id = ?",
                String.class, buyerId, propertyId);
        assertThat(value).isNotBlank();
        return value;
    }

    private void setPropertyStatus(UUID propertyId, PropertyStatus status) {
        jdbcTemplate.update("UPDATE properties SET status = ? WHERE id = ?",
                status.name(), propertyId);
    }

    private void setFavoriteCreatedAt(UUID buyerId, UUID propertyId, String value) {
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = ? WHERE buyer_id = ? AND property_id = ?",
                Timestamp.from(Instant.parse(value)), buyerId, propertyId);
    }

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage62");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        User saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Property persistProperty(User admin, User agent, PropertyStatus status, String label) {
        PropertyCategory category = categoryRepository.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypeRepository
                .findByCategory_Id(CATEGORY_ID, Sort.by("name")).stream()
                .findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(unique(label));
        property.setSlug(unique(label.toLowerCase(Locale.ROOT).replace(' ', '-')));
        property.setDescription("Stage 6.2 favorites integration fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Integration Road");
        property.setLocality("Favorite Locality");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setStatus(status);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setPublishedAt(status == PropertyStatus.PUBLISHED ? Instant.now() : null);
        Property saved = propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(saved.getId());

        PropertyResidentialDetails details = new PropertyResidentialDetails();
        details.setProperty(saved);
        details.setBedrooms((short) 2);
        details.setBathrooms((short) 2);
        details.setBalconies((short) 1);
        details.setFloorNumber((short) 3);
        details.setTotalFloors((short) 10);
        details.setParkingSpaces((short) 1);
        details.setFurnishingStatus("FURNISHED");
        residentialDetailsRepository.saveAndFlush(details);

        PropertyImage image = new PropertyImage();
        image.setProperty(saved);
        image.setStorageProvider("TEST");
        image.setStoragePublicId(saved.getId().toString());
        image.setImageUrl("https://cdn.example.test/" + saved.getId() + ".jpg");
        image.setAltText("Favorite integration image");
        image.setSortOrder(0);
        image.setIsPrimary(true);
        imageRepository.saveAndFlush(image);
        return saved;
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

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private record Session(Cookie cookie, String csrfToken) {
    }
}
