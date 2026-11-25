package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import jakarta.servlet.http.Cookie;

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

/** Real-PostgreSQL verification of Stage 6.4 inquiry API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquiryApiIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PropertyCategoryRepository categoryRepository;
    @Autowired private PropertySubtypeRepository subtypeRepository;
    @Autowired private PropertyRepository propertyRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        for (UUID propertyId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE property_id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE buyer_id = ? OR assigned_agent_id = ?",
                    userId, userId);
        }
        for (UUID propertyId : createdPropertyIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID IN (SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?)", sessionId);
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
    }

    @Test
    void buyerCanSubmitListAndViewOwnInquiry() throws Exception {
        User admin = persistUser("ADMIN", "inq-admin");
        User agent = persistUser("AGENT", "inq-agent");
        User buyer = persistUser("BUYER", "inq-buyer");
        Property property = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Inquiry Villa");
        Session session = login(buyer.getEmail());

        MvcResult submit = mockMvc.perform(post("/api/v1/properties/" + property.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Tour\",\"message\":\"Please contact me\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(property.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("NEW"))
                .andReturn();

        String idText = objectMapperLite(submit.getResponse().getContentAsString(), "data", "id");
        UUID inquiryId = UUID.fromString(idText);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE id = ?", Long.class, inquiryId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT buyer_id FROM inquiries WHERE id = ?", UUID.class, inquiryId)).isEqualTo(buyer.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT assigned_agent_id FROM inquiries WHERE id = ?", UUID.class, inquiryId)).isEqualTo(agent.getId());

        mockMvc.perform(get("/api/v1/inquiries/me").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data[0].id").value(inquiryId.toString()))
                .andExpect(jsonPath("$.data[0].message").doesNotExist())
                .andExpect(jsonPath("$.data[0].propertyTitle").value(property.getTitle()));

        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.id").value(inquiryId.toString()))
                .andExpect(jsonPath("$.data.message").value("Please contact me"))
                .andExpect(jsonPath("$.data.subject").value("Tour"));
    }

    @Test
    void duplicateActiveInquiryReturnsConflictAndDoesNotCreateRow() throws Exception {
        User admin = persistUser("ADMIN", "inq-dup-admin");
        User agent = persistUser("AGENT", "inq-dup-agent");
        User buyer = persistUser("BUYER", "inq-dup-buyer");
        Property property = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Dup Villa");
        Session session = login(buyer.getEmail());

        mockMvc.perform(post("/api/v1/properties/" + property.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"First\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/properties/" + property.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Second\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE buyer_id = ? AND property_id = ?",
                Long.class, buyer.getId(), property.getId());
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void submitRejectsNonPublishedPropertyAndUnknownFields() throws Exception {
        User admin = persistUser("ADMIN", "inq-bad-admin");
        User agent = persistUser("AGENT", "inq-bad-agent");
        User buyer = persistUser("BUYER", "inq-bad-buyer");
        Property draft = persistProperty(admin, agent, PropertyStatus.DRAFT, "Draft Villa");
        Session session = login(buyer.getEmail());

        mockMvc.perform(post("/api/v1/properties/" + draft.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/v1/properties/" + draft.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"buyerId\":\"" + buyer.getId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void historyDetailAndAuthSemanticsAreEnforced() throws Exception {
        User admin = persistUser("ADMIN", "inq-auth-admin");
        User agent = persistUser("AGENT", "inq-auth-agent");
        User otherAgent = persistUser("AGENT", "inq-auth-other-agent");
        User buyer = persistUser("BUYER", "inq-auth-buyer");
        User otherBuyer = persistUser("BUYER", "inq-auth-other-buyer");
        Property property = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "Auth Villa");
        Session buyerSession = login(buyer.getEmail());
        Session otherBuyerSession = login(otherBuyer.getEmail());
        Session agentSession = login(agent.getEmail());
        Session otherAgentSession = login(otherAgent.getEmail());
        Session adminSession = login(admin.getEmail());

        MvcResult submit = mockMvc.perform(post("/api/v1/properties/" + property.getId() + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(buyerSession.cookie())
                        .header(CSRF_HEADER, buyerSession.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Private\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID inquiryId = UUID.fromString(objectMapperLite(submit.getResponse().getContentAsString(), "data", "id"));

        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(agentSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(inquiryId.toString()));
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(adminSession.cookie()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(otherBuyerSession.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(otherAgentSession.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/inquiries/" + UUID.randomUUID()).cookie(buyerSession.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/inquiries/me").cookie(agentSession.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inquiries/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSupportsPaginationSortAndStatusFilter() throws Exception {
        User admin = persistUser("ADMIN", "inq-list-admin");
        User agent = persistUser("AGENT", "inq-list-agent");
        User buyer = persistUser("BUYER", "inq-list-buyer");
        Property p1 = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "List One");
        Property p2 = persistProperty(admin, agent, PropertyStatus.PUBLISHED, "List Two");
        Session session = login(buyer.getEmail());

        UUID i1 = submitInquiry(session, p1.getId(), "m1");
        UUID i2 = submitInquiry(session, p2.getId(), "m2");
        jdbcTemplate.update("UPDATE inquiries SET created_at=?, updated_at=?, status='RESOLVED', resolved_at=now() WHERE id=?",
                Timestamp.from(Instant.parse("2026-11-20T10:00:00Z")), Timestamp.from(Instant.parse("2026-11-20T10:00:00Z")), i1);
        jdbcTemplate.update("UPDATE inquiries SET created_at=?, updated_at=? WHERE id=?",
                Timestamp.from(Instant.parse("2026-11-21T10:00:00Z")), Timestamp.from(Instant.parse("2026-11-21T10:00:00Z")), i2);

        mockMvc.perform(get("/api/v1/inquiries/me").param("sort","newest").param("size","1").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(i2.toString()))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mockMvc.perform(get("/api/v1/inquiries/me").param("sort","oldest").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(i1.toString()));

        mockMvc.perform(get("/api/v1/inquiries/me").param("status","RESOLVED").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(i1.toString()));

        mockMvc.perform(get("/api/v1/inquiries/me").param("status","bad").cookie(session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(get("/api/v1/inquiries/me").param("sort","price_asc").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historicalAssignmentAuthorizationAndLifecycleBoundariesArePreserved() throws Exception {
        User admin = persistUser("ADMIN", "inq-hist-admin");
        User agentA = persistUser("AGENT", "inq-hist-agent-a");
        User agentB = persistUser("AGENT", "inq-hist-agent-b");
        User buyer = persistUser("BUYER", "inq-hist-buyer");
        User otherBuyer = persistUser("BUYER", "inq-hist-other-buyer");
        Property property = persistProperty(admin, agentA, PropertyStatus.PUBLISHED, "Historical Villa");
        Session buyerSession = login(buyer.getEmail());
        Session agentASession = login(agentA.getEmail());
        Session agentBSession = login(agentB.getEmail());
        Session adminSession = login(admin.getEmail());
        Session otherBuyerSession = login(otherBuyer.getEmail());

        UUID inquiryId = submitInquiry(buyerSession, property.getId(), "historical");
        jdbcTemplate.update("UPDATE inquiries SET status='RESOLVED', resolved_at=now(), updated_at=now() WHERE id=?", inquiryId);

        // Reassign property; historical inquiry assignment must remain agentA.
        jdbcTemplate.update("UPDATE properties SET primary_agent_id=?, updated_at=now() WHERE id=?", agentB.getId(), property.getId());
        UUID assignedAgentId = jdbcTemplate.queryForObject("SELECT assigned_agent_id FROM inquiries WHERE id=?", UUID.class, inquiryId);
        assertThat(assignedAgentId).isEqualTo(agentA.getId());

        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(agentASession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAgentId").value(agentA.getId().toString()));
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(agentBSession.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(otherBuyerSession.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(adminSession.cookie()))
                .andExpect(status().isOk());

        // New inquiry after RESOLVED uses current eligible assigned agent (agentB).
        UUID newInquiryId = submitInquiry(buyerSession, property.getId(), "after-resolved");
        UUID newAssigned = jdbcTemplate.queryForObject("SELECT assigned_agent_id FROM inquiries WHERE id=?", UUID.class, newInquiryId);
        assertThat(newAssigned).isEqualTo(agentB.getId());

        // New inquiry after CLOSED also allowed; multiple historical coexist with one active.
        jdbcTemplate.update("UPDATE inquiries SET status='CLOSED', updated_at=now() WHERE id=?", newInquiryId);
        UUID closedFollowUpId = submitInquiry(buyerSession, property.getId(), "after-closed");
        UUID closedFollowUpAssigned = jdbcTemplate.queryForObject("SELECT assigned_agent_id FROM inquiries WHERE id=?", UUID.class, closedFollowUpId);
        assertThat(closedFollowUpAssigned).isEqualTo(agentB.getId());
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE buyer_id=? AND property_id=? AND status IN ('NEW','IN_PROGRESS')",
                Long.class, buyer.getId(), property.getId());
        Long totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE buyer_id=? AND property_id=?",
                Long.class, buyer.getId(), property.getId());
        assertThat(activeCount).isEqualTo(1L);
        assertThat(totalCount).isEqualTo(3L);

        // Property archival preserves inquiry records.
        jdbcTemplate.update("UPDATE properties SET status='ARCHIVED', archived_at=now(), updated_at=now() WHERE id=?", property.getId());
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inquiries WHERE property_id=?", Long.class, property.getId());
        assertThat(count).isEqualTo(2L);
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(buyerSession.cookie()))
                .andExpect(status().isOk());

        // Deactivation denies access per approved policy; reactivation restores eligible historical access.
        jdbcTemplate.update("UPDATE users SET account_status='DEACTIVATED', updated_at=now() WHERE id=?", agentA.getId());
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(agentASession.cookie()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403, 404));

        jdbcTemplate.update("UPDATE users SET account_status='ACTIVE', updated_at=now() WHERE id=?", agentA.getId());
        Session reactivatedAgentSession = login(agentA.getEmail());
        mockMvc.perform(get("/api/v1/inquiries/" + inquiryId).cookie(reactivatedAgentSession.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(inquiryId.toString()));
    }

    private UUID submitInquiry(Session session, UUID propertyId, String message) throws Exception {
        MvcResult submit = mockMvc.perform(post("/api/v1/properties/" + propertyId + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(session.cookie())
                        .header(CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapperLite(submit.getResponse().getContentAsString(), "data", "id"));
    }

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage64");
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
        PropertySubtype subtype = subtypeRepository.findByCategory_Id(CATEGORY_ID, Sort.by("name")).stream()
                .findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(unique(label));
        property.setSlug(unique(label.toLowerCase(Locale.ROOT).replace(' ', '-')));
        property.setDescription("Stage 6.4 inquiry integration fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Integration Road");
        property.setLocality("Inquiry Locality");
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
        return saved;
    }

    private Session login(String email) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = requireSessionCookie(csrfResult);
        String csrfToken = csrfResult.getResponse().getHeader(CSRF_HEADER);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(csrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie sessionCookie = requireSessionCookie(login);
        String rotated = login.getResponse().getHeader(CSRF_HEADER);
        return new Session(sessionCookie, rotated);
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
        if (value.matches("[0-9a-fA-F-]{36}")) return value;
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { decoded = Base64.getMimeDecoder().decode(value); }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static String objectMapperLite(String json, String... path) {
        // Minimal JSON path extraction for test ids; avoids extra ObjectMapper wiring.
        String current = json;
        for (String key : path) {
            int idx = current.indexOf("\"" + key + "\"");
            assertThat(idx).as(key).isGreaterThanOrEqualTo(0);
            int colon = current.indexOf(':', idx);
            current = current.substring(colon + 1).trim();
        }
        if (current.startsWith("\"")) {
            int end = current.indexOf('"', 1);
            return current.substring(1, end);
        }
        int end = current.indexOf(',');
        if (end < 0) end = current.indexOf('}');
        return current.substring(0, end).trim();
    }

    private static String unique(String label) { return label + "-" + UUID.randomUUID(); }
    private record Session(Cookie cookie, String csrfToken) { }
}
