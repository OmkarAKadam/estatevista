package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquirySubmissionHttpConcurrencyIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final String PASSWORD = "Example-Password-123";
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private PasswordEncoder passwordEncoder;

    private final List<UUID> inquiryIds = new ArrayList<>();
    private final List<UUID> propertyIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!propertyIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE property_id = ANY (?)", propertyIds.toArray(UUID[]::new));
        }
        if (!userIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE buyer_id = ANY (?) OR assigned_agent_id = ANY (?)",
                    userIds.toArray(UUID[]::new), userIds.toArray(UUID[]::new));
        }
        if (!propertyIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM properties WHERE id = ANY (?)", propertyIds.toArray(UUID[]::new));
        }
        if (!sessionIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM spring_session_attributes WHERE session_primary_id IN (SELECT primary_id FROM spring_session WHERE session_id = ANY (?))",
                    sessionIds.toArray(String[]::new));
            jdbcTemplate.update("DELETE FROM spring_session WHERE session_id = ANY (?)", sessionIds.toArray(String[]::new));
        }
        if (!userIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ANY (?)", userIds.toArray(UUID[]::new));
        }
    }

    @Test
    void concurrentHttpSubmissionsCreateOneInquiryAndReturnConflicts() throws Exception {
        User agent = persistUser("AGENT", "http-conc-agent");
        User buyer = persistUser("BUYER", "http-conc-buyer");
        User otherBuyer = persistUser("BUYER", "http-conc-other-buyer");
        Property property = persistProperty(agent, "HTTP Concurrent Villa");
        Property independentProperty = persistProperty(agent, "HTTP Independent Villa");
        Session buyerSession = login(buyer.getEmail());
        Session otherBuyerSession = login(otherBuyer.getEmail());

        // independent buyer/property unaffected by concurrent duplicate submissions
        mockMvc.perform(post("/api/v1/properties/" + independentProperty.getId() + "/inquiries")
                        .cookie(otherBuyerSession.cookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, otherBuyerSession.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"independent\"}"))
                .andExpect(status().isCreated());

        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int idx = i;
            tasks.add(() -> {
                start.await();
                MvcResult r = mockMvc.perform(post("/api/v1/properties/" + property.getId() + "/inquiries")
                                .cookie(buyerSession.cookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                                .header(CSRF_HEADER, buyerSession.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subject\":\"s" + idx + "\",\"message\":\"m" + idx + "\"}"))
                        .andReturn();
                return r.getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) statuses.add(f.get(30, TimeUnit.SECONDS));
        executor.shutdownNow();

        long created = statuses.stream().filter(s -> s == 201).count();
        long conflicts = statuses.stream().filter(s -> s == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(workers - 1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);

        Long activeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE buyer_id=? AND property_id=? AND status IN ('NEW','IN_PROGRESS')",
                Long.class, buyer.getId(), property.getId());
        assertThat(activeRows).isEqualTo(1L);
        Long independentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE buyer_id=? AND property_id=?",
                Long.class, otherBuyer.getId(), independentProperty.getId());
        assertThat(independentRows).isEqualTo(1L);

        // Existing inquiry content/timestamps unchanged by duplicate conflicts
        String message = jdbcTemplate.queryForObject("SELECT message FROM inquiries WHERE buyer_id=? AND property_id=? AND status='NEW'",
                String.class, buyer.getId(), property.getId());
        assertThat(message).isIn("m0","m1","m2","m3","m4","m5");
    }

    private User persistUser(String roleName, String label) {
        Role role = roles.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage64");
        user.setLastName(label);
        user.setEmail((label + "-" + UUID.randomUUID() + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        User saved = users.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }

    private Property persistProperty(User agent, String label) {
        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name")).stream().findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(label + " " + UUID.randomUUID());
        property.setSlug((label + "-" + UUID.randomUUID()).toLowerCase(Locale.ROOT).replace(' ', '-'));
        property.setDescription("Stage 6.4 HTTP concurrency fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Concurrency Road");
        property.setLocality("Concurrency Locality");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(agent);
        property.setPublishedAt(Instant.now());
        Property saved = properties.saveAndFlush(property);
        propertyIds.add(saved.getId());
        return saved;
    }

    private Session login(String email) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie(SESSION_COOKIE);
        String csrfToken = csrfResult.getResponse().getHeader(CSRF_HEADER);
        sessionIds.add(sessionIdFromCookie(csrfCookie));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").cookie(csrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN).header(CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie sessionCookie = login.getResponse().getCookie(SESSION_COOKIE);
        sessionIds.add(sessionIdFromCookie(sessionCookie));
        return new Session(sessionCookie, login.getResponse().getHeader(CSRF_HEADER));
    }

    private static String sessionIdFromCookie(Cookie cookie) {
        String value = cookie.getValue();
        if (value.matches("[0-9a-fA-F-]{36}")) return value;
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(value); } catch (IllegalArgumentException e) { decoded = Base64.getMimeDecoder().decode(value); }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private record Session(Cookie cookie, String csrfToken) {}
}
