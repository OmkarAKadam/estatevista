package com.estatevista.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.exception.GlobalExceptionHandler;
import com.estatevista.common.logging.CorrelationIdFilter;
import com.estatevista.security.RateLimitService;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * PostgreSQL-backed verification of the Stage 3.6 corrective D-11 audit hooks.
 *
 * <p>These tests deliberately do not use a rolled-back test transaction: audit
 * writes use REQUIRES_NEW and must be asserted as committed rows.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "app.rate-limit.registration.max-attempts=1",
        "app.rate-limit.registration.window-seconds=60"
})
class SecurityAuditIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final String PREFIX = "s36-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    private final List<String> requestIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> roleIds = new ArrayList<>();

    private String rateLimitScope;
    private String rateLimitIdentity;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private ObjectProvider<AuditEventService> auditEventServiceProvider;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
        RequestContextHolder.resetRequestAttributes();
        for (String requestId : requestIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE request_id = ?", requestId);
        }
        if (rateLimitScope != null && rateLimitIdentity != null) {
            rateLimitService.reset(rateLimitScope, rateLimitIdentity);
        }
        for (UUID userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (UUID roleId : roleIds) {
            jdbcTemplate.update("DELETE FROM roles WHERE id = ?", roleId);
        }
    }

    @Test
    void authenticationLifecycleEventsArePersistedWithSafeMetadata() throws Exception {
        String email = uniqueEmail("lifecycle");
        String registrationRequestId = newRequestId("register");

        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CorrelationIdFilter.HEADER, registrationRequestId)
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        UUID userId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(
                        registration.getResponse().getContentAsString(), "$.data.id"));
        userIds.add(userId);

        Map<String, Object> registrationAudit = singleEvent(
                registrationRequestId, AuditEventService.ACTION_REGISTER);
        assertThat(String.valueOf(registrationAudit.get("actor_id"))).isEqualTo(userId.toString());
        assertSafePayload(registrationAudit, email, PASSWORD);

        MvcResult csrfBootstrap = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = csrfBootstrap.getResponse().getCookie(SESSION_COOKIE);
        assertThat(csrfCookie).isNotNull();
        String bootstrapToken = csrfBootstrap.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(bootstrapToken).isNotBlank();

        String loginRequestId = newRequestId("login-success");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(csrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", bootstrapToken)
                        .header(CorrelationIdFilter.HEADER, loginRequestId)
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie(SESSION_COOKIE);
        assertThat(sessionCookie).isNotNull();
        String rotatedToken = login.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(rotatedToken).isNotBlank();

        Map<String, Object> loginAudit = singleEvent(
                loginRequestId, AuditEventService.ACTION_LOGIN_SUCCESS);
        assertThat(String.valueOf(loginAudit.get("actor_id"))).isEqualTo(userId.toString());
        assertSafePayload(loginAudit, email, PASSWORD, rotatedToken, sessionCookie.getValue());

        MvcResult csrfForFailure = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie failureCsrfCookie = csrfForFailure.getResponse().getCookie(SESSION_COOKIE);
        assertThat(failureCsrfCookie).isNotNull();
        String failureCsrfToken = csrfForFailure.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(failureCsrfToken).isNotBlank();

        String failureRequestId = newRequestId("login-failure");
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(failureCsrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", failureCsrfToken)
                        .header(CorrelationIdFilter.HEADER, failureRequestId)
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "Wrong-Password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        Map<String, Object> failureAudit = singleEvent(
                failureRequestId, AuditEventService.ACTION_LOGIN_FAILURE);
        assertThat(failureAudit.get("actor_id")).isNull();
        assertSafePayload(failureAudit, email, PASSWORD, "Wrong-Password-123");

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                        .cookie(sessionCookie)
                        .header(CorrelationIdFilter.HEADER, newRequestId("me-before-logout")))
                .andExpect(status().isOk())
                .andReturn();
        String logoutToken = me.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(logoutToken).isNotBlank();

        String logoutRequestId = newRequestId("logout");
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie)
                        .header("X-CSRF-TOKEN", logoutToken)
                        .header(CorrelationIdFilter.HEADER, logoutRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Map<String, Object> logoutAudit = singleEvent(
                logoutRequestId, AuditEventService.ACTION_LOGOUT);
        assertThat(String.valueOf(logoutAudit.get("actor_id"))).isEqualTo(userId.toString());
        assertSafePayload(logoutAudit, email, rotatedToken, logoutToken, sessionCookie.getValue());
    }

    @Test
    void registrationOriginRejectionIsPersistedAsCsrfFailure() throws Exception {
        String email = uniqueEmail("origin");
        String requestId = newRequestId("origin");

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.REFERER, "https://evil.example.com/register")
                        .header(CorrelationIdFilter.HEADER, requestId)
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_CSRF_FAILURE);
        assertThat(row.get("actor_id")).isNull();
        assertThat(String.valueOf(row.get("after_data")))
                .contains("INVALID_ORIGIN")
                .contains("ANONYMOUS");
        assertSafePayload(row, email, PASSWORD, "https://evil.example.com");
    }

    @Test
    void untrustedRoleLoginFailureIsPersistedWithSafeMetadata() throws Exception {
        String email = uniqueEmail("untrusted-role");
        persistUser(email, "UNTRUSTED_ROLE");
        String requestId = newRequestId("untrusted-role-login");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CorrelationIdFilter.HEADER, requestId)
                        .with(csrf())
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_LOGIN_FAILURE);
        assertThat(row.get("actor_id")).isNull();
        assertSafePayload(row, email, PASSWORD, "UNTRUSTED_ROLE");
    }

    @Test
    void websocketAuthFailureIsPersistedWithTrustedActorAndSafeDestination() {
        String email = uniqueEmail("ws");
        User user = persistUser(email);
        String requestId = newRequestId("ws");
        MDC.put(CorrelationIdFilter.MDC_KEY, requestId);

        auditEventService.recordWebSocketAuthFailure(
                user.getId().toString(), "/topic/conversations");

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_WS_AUTH_FAILURE);
        assertThat(String.valueOf(row.get("actor_id"))).isEqualTo(user.getId().toString());
        assertThat(String.valueOf(row.get("after_data")))
                .contains("FORBIDDEN")
                .contains("/topic/conversations");
        assertSafePayload(row, email, PASSWORD, "hello");
    }

    @Test
    void csrfFailureIsPersistedAsAnonymousSecurityEvent() throws Exception {
        String requestId = newRequestId("csrf");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CorrelationIdFilter.HEADER, requestId)
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(uniqueEmail("csrf"), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_CSRF_FAILURE);
        assertThat(row.get("actor_id")).isNull();
        assertThat(String.valueOf(row.get("after_data"))).contains("INVALID_CSRF").contains("ANONYMOUS");
        assertSafePayload(row, PASSWORD);
    }

    @Test
    void chainAuthorizationDenialIsPersistedWithTrustedActor() throws Exception {
        String email = uniqueEmail("authz");
        User user = persistUser(email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CorrelationIdFilter.HEADER, newRequestId("authz-login"))
                        .with(csrf())
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie(SESSION_COOKIE);
        assertThat(sessionCookie).isNotNull();

        String requestId = newRequestId("authz");
        mockMvc.perform(get("/api/v1/admin/security-audit-probe")
                        .cookie(sessionCookie)
                        .header(CorrelationIdFilter.HEADER, requestId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_ACCESS_DENIED);
        assertThat(String.valueOf(row.get("actor_id"))).isEqualTo(user.getId().toString());
        assertThat(String.valueOf(row.get("after_data"))).contains("FORBIDDEN");
        assertSafePayload(row, email, PASSWORD);
    }

    @Test
    void resourceLevelForbiddenIsPersistedExactlyOnceWithoutResourceContent() {
        String requestId = newRequestId("resource");
        MockHttpServletRequest request =
                new MockHttpServletRequest("PATCH", "/api/v1/private-resource/123");
        request.setRemoteAddr("198.51.100.20");
        request.addHeader(CorrelationIdFilter.HEADER, requestId);

        new GlobalExceptionHandler(auditEventServiceProvider).handleApiException(
                new ApiException(ErrorCode.FORBIDDEN, "Private conversation content"), request);
        new GlobalExceptionHandler(auditEventServiceProvider).handleApiException(
                new ApiException(ErrorCode.FORBIDDEN, "Private conversation content"), request);

        Map<String, Object> row = singleEvent(requestId, AuditEventService.ACTION_ACCESS_DENIED);
        assertThat(String.valueOf(row.get("after_data"))).contains("FORBIDDEN");
        assertSafePayload(row, "Private conversation content", "123");
    }

    @Test
    void rateLimitRejectionIsPersistedWithoutRawIdentity() {
        String requestId = newRequestId("rate-limit");
        MDC.put(CorrelationIdFilter.MDC_KEY, requestId);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        request.setRemoteAddr("203.0.113.20");
        request.addHeader(CorrelationIdFilter.HEADER, requestId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        rateLimitScope = RateLimitService.SCOPE_REGISTRATION;
        rateLimitIdentity = "audit-rate-" + UUID.randomUUID();
        rateLimitService.acquire(rateLimitScope, rateLimitIdentity);

        assertThatThrownBy(() -> rateLimitService.acquire(rateLimitScope, rateLimitIdentity))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        Map<String, Object> row = singleEvent(
                requestId, AuditEventService.ACTION_RATE_LIMIT_REJECTED);
        assertThat(String.valueOf(row.get("after_data")))
                .contains("REJECTED")
                .contains("RATE_LIMIT")
                .contains("registration");
        assertSafePayload(row, rateLimitIdentity, "203.0.113.20");
    }

    private Map<String, Object> singleEvent(String requestId, String action) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, actor_id, entity_id, after_data, request_id, ip_address "
                        + "FROM audit_logs WHERE request_id = ?",
                requestId);
        assertThat(rows).as("audit rows for %s", requestId).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("action")).isEqualTo(action);
        assertThat(row.get("request_id")).isEqualTo(requestId);
        return row;
    }

    private static void assertSafePayload(Map<String, Object> row, String... forbiddenValues) {
        String payload = String.valueOf(row.get("after_data")).toLowerCase(Locale.ROOT);
        assertThat(payload).contains("outcome");
        assertThat(payload).doesNotContain(
                "password", "token", "session", "cookie", "authorization", "bearer");
        for (String value : forbiddenValues) {
            if (value != null && !value.isBlank()) {
                assertThat(payload)
                        .as("audit payload must not contain sensitive value")
                        .doesNotContain(value.toLowerCase(Locale.ROOT));
            }
        }
    }

    private User persistUser(String email) {
        return persistUser(email, "BUYER");
    }

    private User persistUser(String email, String roleName) {
        Role role = roleRepository.findByName(roleName).orElseGet(() -> {
            Role created = new Role();
            created.setName(roleName);
            Role saved = roleRepository.saveAndFlush(created);
            roleIds.add(saved.getId());
            return saved;
        });
        User user = new User();
        user.setRole(role);
        user.setFirstName("Audit");
        user.setLastName("User");
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        User saved = userRepository.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }

    private String newRequestId(String suffix) {
        String requestId = PREFIX + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        requestIds.add(requestId);
        return requestId;
    }

    private static String registerBody(String email) {
        return """
                {
                  "firstName": "Audit",
                  "lastName": "User",
                  "email": "%s",
                  "password": "%s",
                  "phone": "+919876543210"
                }
                """.formatted(email, PASSWORD);
    }

    private static String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private static String uniqueEmail(String suffix) {
        return suffix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueIp() {
        return "192.0.2." + ThreadLocalRandom.current().nextInt(1, 254);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
