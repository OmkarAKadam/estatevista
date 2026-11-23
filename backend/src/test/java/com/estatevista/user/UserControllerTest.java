package com.estatevista.user;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.config.AppProperties;
import com.estatevista.config.CorsConfig;
import com.estatevista.config.SecurityConfig;
import com.estatevista.user.dto.UserProfileResponse;

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

@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class UserControllerTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UserProfileResponse PROFILE = new UserProfileResponse(
            USER_ID, "John", "Doe", "john@example.com", "+919876543210", "BUYER", "ACTIVE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @BeforeEach
    void setUp() {
        when(userService.getProfile(any())).thenReturn(PROFILE);
        when(userService.updateProfile(any(), any())).thenReturn(PROFILE);
    }

    @Test
    void currentUserEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void currentUserReturnsTheAuthenticatedPrincipalProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("john@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("passwordHash"))));
    }

    @Test
    void updateRequiresACsrfToken() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(userService);
    }

    @Test
    void authenticatedUpdateWithCsrfSucceeds() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\",\"phone\":\"+441234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile updated"))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()));
    }

    @Test
    void roleEscalationIsRejected() throws Exception {
        assertProtectedFieldRejected("role", "ADMIN");
    }

    @Test
    void accountStatusChangeIsRejected() throws Exception {
        assertProtectedFieldRejected("accountStatus", "SUSPENDED");
    }

    @Test
    void passwordHashChangeIsRejected() throws Exception {
        assertProtectedFieldRejected("passwordHash", "{bcrypt}$2a$12$attacker-supplied");
    }

    @Test
    void identifierChangeIsRejected() throws Exception {
        assertProtectedFieldRejected("id", UUID.randomUUID().toString());
    }

    @Test
    void emailChangeIsRejectedUntilVerificationExists() throws Exception {
        assertProtectedFieldRejected("email", "attacker@example.com");
    }

    @Test
    void malformedPrincipalReturnsUnauthorized() throws Exception {
        when(userService.updateProfile(any(), any()))
                .thenThrow(new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void updateWithoutCsrfIsRejectedEvenWhenAnonymous() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private void assertProtectedFieldRejected(String field, String value) throws Exception {
        String json = "{\"" + field + "\":\"" + value + "\"}";

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == '" + field + "')]").exists());

        verifyNoInteractions(userService);
    }
}
