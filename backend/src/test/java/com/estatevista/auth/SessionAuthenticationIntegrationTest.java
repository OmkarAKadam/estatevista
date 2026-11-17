package com.estatevista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class SessionAuthenticationIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String SESSION_COOKIE = "EV_SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loginEstablishesPersistentAuthenticatedSessionAndMeReturnsSafeUser() throws Exception {
        User user = persistUser("login.ok@example.com", AccountStatus.ACTIVE);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("  LOGIN.OK@example.com  ", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("login.ok@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(cookie().exists(SESSION_COOKIE))
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andReturn();

        Cookie sessionCookie = requireSessionCookie(login);
        assertThat(countSessionRows(sessionCookie)).isEqualTo(1);
        assertThat(login.getResponse().getContentAsString()).doesNotContain(sessionCookie.getValue());
        assertThat(login.getResponse().getContentAsString()).doesNotContain(PASSWORD);

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("login.ok@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(sessionCookie.getValue()))));
    }

    @Test
    void invalidCredentialsReturnGenericUnauthorizedAndDoNotCreateAuthenticatedSession() throws Exception {
        persistUser("known@example.com", AccountStatus.ACTIVE);

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("known@example.com", "Wrong-Password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andReturn();
        assertAnonymousSession(wrongPassword);

        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com", "Wrong-Password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"))
                .andReturn();
        assertAnonymousSession(unknown);
    }

    @Test
    void inactiveAccountCannotAuthenticateAndMessageDoesNotDiscloseStatus() throws Exception {
        persistUser("inactive@example.com", AccountStatus.INACTIVE);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("inactive@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"))
                .andReturn();
        assertAnonymousSession(result);
    }

    @Test
    void malformedStoredPasswordHashFailsSafely() throws Exception {
        User user = persistUser("malformed@example.com", AccountStatus.ACTIVE);
        user.setPasswordHash("not-a-valid-hash");
        userRepository.saveAndFlush(user);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("malformed@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"))
                .andReturn();
        assertAnonymousSession(result);
    }

    @Test
    void loginRequiresValidCsrfToken() throws Exception {
        persistUser("csrf.login@example.com", AccountStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("csrf.login@example.com", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("csrf.login@example.com", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void csrfBootstrapReturnsUsableTokenForStateChangingRequest() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        Cookie sessionCookie = requireSessionCookie(csrfResult);
        String token = com.jayway.jsonpath.JsonPath.read(
                csrfResult.getResponse().getContentAsString(), "$.data.token");
        assertThat(csrfResult.getResponse().getHeader("X-CSRF-TOKEN")).isEqualTo(token);

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(sessionCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void sessionFixationChangesSessionIdOnAuthentication() throws Exception {
        persistUser("fixation@example.com", AccountStatus.ACTIVE);

        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie beforeLoginCookie = requireSessionCookie(csrfResult);
        String oldSessionId = beforeLoginCookie.getValue();
        String token = com.jayway.jsonpath.JsonPath.read(
                csrfResult.getResponse().getContentAsString(), "$.data.token");

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(beforeLoginCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("fixation@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie afterLoginCookie = requireSessionCookie(login);
        assertThat(afterLoginCookie.getValue()).isNotEqualTo(oldSessionId);
        assertThat(oldSessionId).isNotBlank();

        mockMvc.perform(get("/api/v1/auth/me").cookie(afterLoginCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("fixation@example.com"));
    }

    @Test
    void logoutInvalidatesSessionAndRequiresValidCsrf() throws Exception {
        persistUser("logout@example.com", AccountStatus.ACTIVE);

        MvcResult login = loginAs("logout@example.com", PASSWORD);
        Cookie sessionCookie = requireSessionCookie(login);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie)
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logout successful"))
                .andReturn();

        Cookie cleared = logout.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(countSessionRows(sessionCookie)).isZero();

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousMeReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private MvcResult loginAs(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie %s", SESSION_COOKIE).isNotNull();
        assertThat(cookie.getValue()).as("session cookie value").isNotBlank();
        return cookie;
    }

    private long countSessionRows(Cookie cookie) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
                Long.class,
                sessionIdFromCookie(cookie));
    }

    private String sessionIdFromCookie(Cookie cookie) {
        String value = cookie.getValue();
        if (value.matches("[0-9a-fA-F-]{36}")) {
            return value;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            decoded = Base64.getMimeDecoder().decode(value);
        }
        String sessionId = new String(decoded, StandardCharsets.UTF_8);
        assertThat(sessionId).as("decoded session id").isNotBlank();
        return sessionId;
    }

    private void assertAnonymousSession(MvcResult result) throws Exception {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            return;
        }
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private User persistUser(String email, AccountStatus status) {
        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        User user = new User();
        user.setRole(buyerRole);
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(status);
        return userRepository.saveAndFlush(user);
    }

    private static String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }
}
