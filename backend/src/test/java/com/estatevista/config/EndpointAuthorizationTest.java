package com.estatevista.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.estatevista.audit.AuditEventService;
import com.estatevista.auth.AuthService;
import com.estatevista.property.PropertyService;
import com.estatevista.security.RateLimitService;
import com.estatevista.user.UserService;

/**
 * Request-level authorization matrix for Stage 3.5.
 *
 * <p>Test-only probe endpoints stand in for business controllers that are not
 * implemented until later stages. They verify that request matchers enforce role
 * boundaries, CSRF, CORS, deny-by-default, and the WebSocket handshake gate
 * without a database.</p>
 */
@WebMvcTest
@Import({SecurityConfig.class, CorsConfig.class, EndpointAuthorizationTest.ProbeController.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class EndpointAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private AuditEventService auditEventService;

    @MockitoBean
    private PropertyService propertyService;

    @Test
    void adminPrefixAllowsOnlyAdminAndStillRequiresCsrfForWrites() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/admin/probe").with(user("buyer").roles("BUYER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/probe").with(user("agent").roles("AGENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/probe").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/probe")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/probe")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void agentSelfPrefixAllowsOnlyAgents() throws Exception {
        mockMvc.perform(get("/api/v1/agents/me/probe"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/agents/me/probe").with(user("buyer").roles("BUYER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents/me/probe").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents/me/probe").with(user("agent").roles("AGENT")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agents/me/probe")
                        .with(user("agent").roles("AGENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/agents/me/probe")
                        .with(user("agent").roles("AGENT"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void unknownAuthorityAndClientSuppliedRoleHeadersAreDenied() throws Exception {
        var unknownAuthority = user("guest").authorities(new SimpleGrantedAuthority("ROLE_GUEST"));

        mockMvc.perform(get("/api/v1/admin/probe").with(unknownAuthority))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents/me/probe")
                        .with(user("guest").authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/probe")
                        .with(user("buyer").roles("BUYER"))
                        .header("X-Roles", "ADMIN")
                        .param("role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedRoutesRemainDeniedForEveryRole() throws Exception {
        mockMvc.perform(get("/api/v1/probe/unspecified"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/probe/unspecified").with(user("buyer").roles("BUYER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/probe/unspecified").with(user("agent").roles("AGENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/probe/unspecified").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void websocketHandshakeRequiresAnAuthenticatedSession() throws Exception {
        mockMvc.perform(get("/ws"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/ws").with(user("buyer").roles("BUYER")))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsPublicOnlyForGet() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/actuator/health").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/actuator/health")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleProtectedPreflightStillEnforcesExactCorsOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/admin/probe")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500"));

        mockMvc.perform(options("/api/v1/admin/probe")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void authenticationBootstrapRoutesRemainReachableWithoutChangingRbacRules() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/admin/probe")
        String adminRead() {
            return "admin-read";
        }

        @PostMapping("/api/v1/admin/probe")
        String adminWrite() {
            return "admin-write";
        }

        @GetMapping("/api/v1/agents/me/probe")
        String agentRead() {
            return "agent-read";
        }

        @PostMapping("/api/v1/agents/me/probe")
        String agentWrite() {
            return "agent-write";
        }

        @GetMapping("/api/v1/probe/unspecified")
        String unspecified() {
            return "unspecified";
        }

        @GetMapping("/ws")
        String websocket() {
            return "handshake";
        }

        @GetMapping("/actuator/health")
        String actuatorHealth() {
            return "{\"status\":\"UP\"}";
        }
    }
}
