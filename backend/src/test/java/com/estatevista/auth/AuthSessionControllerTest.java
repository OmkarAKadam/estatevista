package com.estatevista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.auth.dto.AuthenticatedUserResponse;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.config.AppProperties;
import com.estatevista.config.CorsConfig;
import com.estatevista.config.SecurityConfig;
import com.estatevista.security.RateLimitService;

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
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class AuthSessionControllerTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final AuthenticatedUserResponse USER = new AuthenticatedUserResponse(
            USER_ID, "Jane", "Doe", "jane@example.com", "BUYER", "ACTIVE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private AuditEventService auditEventService;

    @Test
    void csrfBootstrapReturnsTokenOnlyInHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().exists("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("CSRF token issued"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.headerName").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.headerName").doesNotExist())
                .andReturn();

        String token = result.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(token).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
    }

    @Test
    void csrfHeaderIsCorsExposedOnlyForAllowedOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-CSRF-TOKEN")));

        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header(HttpHeaders.ORIGIN, "https://unapproved.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void loginRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void loginRejectsInvalidCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void loginWithValidCsrfReturnsSafeUser() throws Exception {
        when(authService.authenticate(any())).thenReturn(USER);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("jane@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Example-Password-123"))));

        verify(rateLimitService).acquire(eq(RateLimitService.SCOPE_LOGIN), anyString());
        verify(rateLimitService).reset(eq(RateLimitService.SCOPE_LOGIN), anyString());
        verify(auditEventService).recordLoginSuccess(USER_ID);
    }

    @Test
    void loginReturnsGenericUnauthorizedOnInvalidCredentials() throws Exception {
        when(authService.authenticate(any()))
                .thenThrow(new ApiException(ErrorCode.UNAUTHORIZED, "Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"));

        verify(rateLimitService).acquire(eq(RateLimitService.SCOPE_LOGIN), anyString());
        verify(auditEventService).recordLoginFailure();
    }

    @Test
    void loginWithUntrustedAccountIsAuditedAsFailure() throws Exception {
        AuthenticatedUserResponse untrusted = new AuthenticatedUserResponse(
                USER_ID, "Jane", "Doe", "jane@example.com", "UNKNOWN_ROLE", "ACTIVE");
        when(authService.authenticate(any())).thenReturn(untrusted);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verify(auditEventService).recordLoginFailure();
        verify(auditEventService, never()).recordLoginSuccess(any());
    }

    @Test
    void loginRejectsUnknownFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Example-Password-123",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(authService);
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedMeReturnsSafeUser() throws Exception {
        when(authService.currentUser(any())).thenReturn(USER);

        mockMvc.perform(get("/api/v1/auth/me").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("jane@example.com"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void logoutRequiresAuthenticationAndCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void authenticatedLogoutWithValidCsrfSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logout successful"));

        verify(auditEventService).recordLogout(USER_ID);
    }

    @Test
    void loginRegeneratesCsrfTokenAndRejectsPreLoginToken() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String preLoginToken = csrfResult.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(preLoginToken).isNotBlank();
        var session = csrfResult.getRequest().getSession(false);

        when(authService.authenticate(any())).thenReturn(USER);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", preLoginToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginBody()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-CSRF-TOKEN"))
                .andReturn();

        String postLoginToken = loginResult.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(postLoginToken).isNotBlank().isNotEqualTo(preLoginToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", preLoginToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", postLoginToken))
                .andExpect(status().isOk());
    }

    private static String validLoginBody() {
        return """
                {
                  "email": "jane@example.com",
                  "password": "Example-Password-123"
                }
                """;
    }
}
