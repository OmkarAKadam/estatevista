package com.estatevista.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.auth.dto.RegisteredUserResponse;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.config.AppProperties;
import com.estatevista.config.CorsConfig;
import com.estatevista.config.SecurityConfig;
import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.BeforeEach;
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

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class AuthControllerTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final RegisteredUserResponse REGISTERED = new RegisteredUserResponse(
            USER_ID, "John", "Doe", "john@example.com", "BUYER", "ACTIVE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        when(authService.register(any())).thenReturn(REGISTERED);
    }

    @Test
    void registrationIsPublicCsrfExemptAndReturnsSafeCreatedEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Account registered successfully"))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.firstName").value("John"))
                .andExpect(jsonPath("$.data.lastName").value("Doe"))
                .andExpect(jsonPath("$.data.email").value("john@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("passwordHash"))));

        verify(rateLimitService).acquire(eq(RateLimitService.SCOPE_REGISTRATION), anyString());
        verify(auditEventService).recordRegistration(USER_ID);
    }

    @Test
    void registrationWithoutOriginOrRefererIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("Request origin is not allowed"));

        verifyNoInteractions(authService);
    }

    @Test
    void registrationWithAnAllowedRefererAndNoOriginSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.REFERER, ORIGIN + "/pages/register.html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void registrationWithADisallowedOriginIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleEscalationFieldIsRejected() throws Exception {
        assertUnknownFieldRejected("role", "ADMIN");
    }

    @Test
    void accountStatusFieldIsRejected() throws Exception {
        assertUnknownFieldRejected("accountStatus", "ACTIVE");
    }

    @Test
    void passwordHashFieldIsRejected() throws Exception {
        assertUnknownFieldRejected("passwordHash", "{bcrypt}$2a$12$attacker-supplied");
    }

    @Test
    void identifierFieldIsRejected() throws Exception {
        assertUnknownFieldRejected("id", USER_ID.toString());
    }

    @Test
    void invalidEmailIsRejectedWithFieldDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {
                                  "firstName": "John",
                                  "lastName": "Doe",
                                  "email": "not-an-email",
                                  "password": "Example-Password-123"
                                }
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'email')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void shortPasswordIsRejectedWithFieldDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {
                                  "firstName": "John",
                                  "lastName": "Doe",
                                  "email": "john@example.com",
                                  "password": "short"
                                }
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'password')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void missingRequiredFieldsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'firstName')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'lastName')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'password')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void duplicateEmailReturnsAConflictEnvelope() throws Exception {
        when(authService.register(any()))
                .thenThrow(new ApiException(ErrorCode.CONFLICT, "An account with that email address already exists"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value("An account with that email address already exists"));
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(validBody()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void malformedJsonReturnsTheBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    private void assertUnknownFieldRejected(String field, String value) throws Exception {
        String json = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"email\":\"john@example.com\","
                + "\"password\":\"" + PASSWORD + "\",\"" + field + "\":\"" + value + "\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == '" + field + "')]").exists());

        verifyNoInteractions(authService);
    }

    private static String validBody() {
        return body("""
                {
                  "firstName": "John",
                  "lastName": "Doe",
                  "email": "john@example.com",
                  "password": "Example-Password-123",
                  "phone": "+919876543210"
                }
                """);
    }

    private static String body(String json) {
        return json;
    }
}
