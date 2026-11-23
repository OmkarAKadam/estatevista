package com.estatevista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real-PostgreSQL verification that login authorities are database-derived and
 * that an existing session cannot keep stale role or status privileges.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
@Import(RbacSessionIntegrationTest.RoleProbeController.class)
class RbacSessionIntegrationTest {

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
    private EntityManager entityManager;

    @Test
    void loginGrantsOnlyTheDatabaseRoleAndNeverClientSuppliedRoles() throws Exception {
        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        Role agentRole = roleRepository.findByName("AGENT").orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();

        Cookie buyer = loginAs(persistUser("rbac-buyer@example.com", buyerRole, AccountStatus.ACTIVE));
        mockMvc.perform(get("/api/v1/auth/me").cookie(buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("BUYER"));
        assertForbidden(get("/api/v1/admin/rbac-probe").cookie(buyer));
        assertForbidden(get("/api/v1/agents/me/rbac-probe").cookie(buyer));
        assertForbidden(get("/api/v1/admin/rbac-probe")
                .cookie(buyer)
                .header("X-Roles", "ADMIN")
                .param("role", "ADMIN"));

        Cookie agent = loginAs(persistUser("rbac-agent@example.com", agentRole, AccountStatus.ACTIVE));
        assertOk(get("/api/v1/agents/me/rbac-probe").cookie(agent));
        assertForbidden(get("/api/v1/admin/rbac-probe").cookie(agent));

        Cookie admin = loginAs(persistUser("rbac-admin@example.com", adminRole, AccountStatus.ACTIVE));
        assertOk(get("/api/v1/admin/rbac-probe").cookie(admin));
        assertForbidden(get("/api/v1/agents/me/rbac-probe").cookie(admin));
    }

    @Test
    void roleAndStatusChangesAreRefreshedOnAnExistingSession() throws Exception {
        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        Role agentRole = roleRepository.findByName("AGENT").orElseThrow();
        User user = persistUser("rbac-change@example.com", buyerRole, AccountStatus.ACTIVE);
        Cookie session = loginAs(user);

        assertForbidden(get("/api/v1/agents/me/rbac-probe").cookie(session));

        user.setRole(agentRole);
        userRepository.saveAndFlush(user);
        entityManager.flush();
        entityManager.clear();

        assertOk(get("/api/v1/agents/me/rbac-probe").cookie(session));
        assertForbidden(get("/api/v1/admin/rbac-probe").cookie(session));

        User reloaded = userRepository.findByEmailIgnoreCase(user.getEmail()).orElseThrow();
        reloaded.setAccountStatus(AccountStatus.INACTIVE);
        userRepository.saveAndFlush(reloaded);
        entityManager.flush();
        entityManager.clear();

        assertUnauthorized(get("/api/v1/auth/me").cookie(session));
        assertUnauthorized(get("/api/v1/agents/me/rbac-probe").cookie(session));
    }

    @Test
    void unknownDatabaseRoleCannotEstablishASession() throws Exception {
        Role unknownRole = new Role();
        unknownRole.setName("GUEST_RBAC_" + UUID.randomUUID().toString().substring(0, 8));
        unknownRole.setDescription("Unsupported role used for fail-closed verification");
        roleRepository.saveAndFlush(unknownRole);
        User user = persistUser("rbac-unknown@example.com", unknownRole, AccountStatus.ACTIVE);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            assertUnauthorized(get("/api/v1/auth/me"));
        } else {
            assertUnauthorized(get("/api/v1/auth/me").cookie(cookie));
        }
    }

    @Test
    void websocketHandshakePathStillRequiresAuthenticationInTheFullApplication() throws Exception {
        assertUnauthorized(get("/ws"));
    }

    private void assertOk(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private void assertForbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private void assertUnauthorized(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private Cookie loginAs(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.getValue()).as("session cookie value").isNotBlank();
        return cookie;
    }

    private User persistUser(String email, Role role, AccountStatus status) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Role");
        user.setLastName("Test");
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

    @RestController
    static class RoleProbeController {

        @GetMapping("/api/v1/admin/rbac-probe")
        String admin() {
            return "admin";
        }

        @GetMapping("/api/v1/agents/me/rbac-probe")
        String agent() {
            return "agent";
        }
    }
}
