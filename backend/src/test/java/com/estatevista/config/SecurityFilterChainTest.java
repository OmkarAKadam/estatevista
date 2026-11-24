package com.estatevista.config;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.estatevista.audit.AuditEventService;
import com.estatevista.auth.AuthService;
import com.estatevista.favorite.service.FavoriteService;
import com.estatevista.property.PropertyService;
import com.estatevista.property.dto.ReferenceDataResponse;
import com.estatevista.property.image.PropertyImageService;
import com.estatevista.security.RateLimitService;
import com.estatevista.user.UserService;

/**
 * Verifies the deny-by-default security shell and the standard error envelope
 * for authentication, authorization, and CSRF failures without starting a
 * database-backed application context.
 */
@WebMvcTest
@Import({SecurityConfig.class, CorsConfig.class, SecurityFilterChainTest.ProbeController.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class SecurityFilterChainTest {

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

    @MockitoBean
    private PropertyImageService propertyImageService;

    @MockitoBean
    private FavoriteService favoriteService;

    @Test
    void unauthenticatedRequestReturnsUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.path").value("/api/v1/probe/read"));

        verifyNoInteractions(auditEventService);
    }

    @Test
    void authenticatedRequestDeniedByPolicyReturnsForbiddenEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/read").with(user("probe")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("Access is denied"));

        verify(auditEventService, times(1)).recordAccessDenied(any());
        verify(auditEventService, times(0)).recordCsrfFailure(any());
    }

    @Test
    void mutatingRequestWithoutCsrfTokenReturnsForbiddenEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/probe/write")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user("probe")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verify(auditEventService, times(1)).recordCsrfFailure(any());
        verify(auditEventService, times(0)).recordAccessDenied(any());
    }

    @Test
    void privateApiAndDeniedResponsesUseNoStore() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        mockMvc.perform(post("/api/v1/probe/write")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user("probe")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void nonExposedActuatorPathsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void websocketHandshakePathRemainsDenied() throws Exception {
        mockMvc.perform(get("/ws"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void registrationEndpointIsOpenWithoutCsrfButStillOriginGuarded() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void disallowedRegistrationOriginIsAuditedOnceWithoutChangingTheEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.REFERER, "https://evil.example.com/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("Request origin is not allowed"));

        verify(auditEventService, times(1)).recordOriginRejected(any());
        verify(auditEventService, times(0)).recordCsrfFailure(any());
        verify(auditEventService, times(0)).recordAccessDenied(any());
    }

    @Test
    void multipleOriginHeadersAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void multipleRefererHeadersAreRejectedWhenOriginMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.REFERER, "http://localhost:5500/register")
                        .header(HttpHeaders.REFERER, "https://evil.example.com/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void currentUserEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedProfileUpdateStillRequiresCsrf() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user("33333333-3333-3333-3333-333333333333"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void allowedOriginPreflightReturnsCredentialedCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/probe/read")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-CSRF-TOKEN")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600"));
    }

    @Test
    void preflightRejectsHeadersOutsideTheApprovedAllowList() throws Exception {
        mockMvc.perform(options("/api/v1/probe/read")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-requested-with"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void hardenedSecurityHeadersArePresentOnPublicResponses() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void hstsIsPresentOnSecureResponses() throws Exception {
        mockMvc.perform(get("/api/v1/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("includeSubDomains")));
    }

    @Test
    void correlationIdIsEchoedAndCanBeSuppliedByTheCaller() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "stage36-request-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "stage36-request-1"));
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void disallowedOriginPreflightIsRejectedWithoutCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/probe/read")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void assignmentRouteUsesAdminMatcherAndRequiresCsrf() throws Exception {
        String admin = "11111111-1111-1111-1111-111111111111";
        String agent = "22222222-2222-2222-2222-222222222222";
        String body = "{\"primaryAgentId\":\"" + agent + "\"}";

        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment",
                        "33333333-3333-3333-3333-333333333333")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user(admin).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment",
                        "33333333-3333-3333-3333-333333333333")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user(agent).roles("AGENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment",
                        "33333333-3333-3333-3333-333333333333")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user(admin).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(patch("/api/v1/admin/properties/{propertyId}/assignment",
                        "33333333-3333-3333-3333-333333333333")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verify(auditEventService, times(1)).recordCsrfFailure(any());
        verify(auditEventService, times(1)).recordAccessDenied(any());
    }

    @Test
    void referenceDataLookupsAreExplicitlyPublicAndReadOnly() throws Exception {
        when(propertyService.getCategories()).thenReturn(List.<ReferenceDataResponse>of());
        when(propertyService.getSubtypes(null)).thenReturn(List.<ReferenceDataResponse>of());
        when(propertyService.getAmenities()).thenReturn(List.<ReferenceDataResponse>of());

        for (String path : List.of(
                "/api/v1/property-categories",
                "/api/v1/property-subtypes",
                "/api/v1/amenities")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.meta").doesNotExist());
        }

        verify(propertyService).getCategories();
        verify(propertyService).getSubtypes(null);
        verify(propertyService).getAmenities();
        verifyNoInteractions(auditEventService);
    }

    @Test
    void referenceDataPathsRejectWriteMethodsUnderDenyByDefault() throws Exception {
        for (String path : List.of(
                "/api/v1/property-categories",
                "/api/v1/property-subtypes",
                "/api/v1/amenities")) {
            mockMvc.perform(post(path)
                            .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                            .with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mockMvc.perform(put(path)
                            .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                            .with(user("11111111-1111-1111-1111-111111111111").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
            mockMvc.perform(delete(path)
                            .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                            .with(user("11111111-1111-1111-1111-111111111111").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }

        verifyNoInteractions(propertyService);
    }

    @RestController
    @RequestMapping("/api/v1/probe")
    static class ProbeController {

        @GetMapping("/read")
        String read() {
            return "ok";
        }

        @PostMapping("/write")
        String write() {
            return "ok";
        }
    }
}
