package com.estatevista.favorite;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.config.AppProperties;
import com.estatevista.config.CorsConfig;
import com.estatevista.config.SecurityConfig;
import com.estatevista.favorite.controller.FavoriteController;
import com.estatevista.favorite.dto.BatchFavoriteStatusResponse;
import com.estatevista.favorite.dto.FavoriteListResponse;
import com.estatevista.favorite.dto.FavoriteMutationResponse;
import com.estatevista.favorite.dto.FavoriteStatusResponse;
import com.estatevista.favorite.dto.RestrictedFavoritePropertyResponse;
import com.estatevista.favorite.service.FavoriteService;
import com.estatevista.favorite.service.FavoriteService.AddResult;
import com.estatevista.property.dto.PageMeta;
import com.estatevista.property.dto.PagedResponse;
import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract tests for exactly the five approved favorites routes. */
@WebMvcTest(controllers = FavoriteController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class FavoriteControllerTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final UUID PROPERTY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_PROPERTY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FavoriteService service;
    @MockitoBean
    private RateLimitService rateLimitService;
    @MockitoBean
    private AuditEventService auditEventService;

    private static RequestPostProcessor buyer() {
        return user("buyer").roles("BUYER");
    }

    private static RequestPostProcessor agent() {
        return user("agent").roles("AGENT");
    }

    @Test
    void addReturnsCreatedEnvelopeAndNoStore() throws Exception {
        when(service.add(any(Authentication.class), eq(PROPERTY_ID)))
                .thenReturn(new AddResult(new FavoriteMutationResponse(PROPERTY_ID, true), true));

        mockMvc.perform(post("/api/v1/favorites/" + PROPERTY_ID)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Property added to favorites"))
                .andExpect(jsonPath("$.data.propertyId").value(PROPERTY_ID.toString()))
                .andExpect(jsonPath("$.data.isFavorite").value(true));
    }

    @Test
    void duplicateAddReturnsOk() throws Exception {
        when(service.add(any(Authentication.class), eq(PROPERTY_ID)))
                .thenReturn(new AddResult(new FavoriteMutationResponse(PROPERTY_ID, true), false));

        mockMvc.perform(post("/api/v1/favorites/" + PROPERTY_ID)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFavorite").value(true));
    }

    @Test
    void removeReturnsEmptyNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/favorites/" + PROPERTY_ID)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().string(""));

        verify(service).remove(any(Authentication.class), eq(PROPERTY_ID));
    }

    @Test
    void listUsesDataAndMetaSiblings() throws Exception {
        PagedResponse<FavoriteListResponse> body = new PagedResponse<>(
                true,
                List.of(new FavoriteListResponse(
                        PROPERTY_ID,
                        Instant.parse("2026-11-24T10:30:00Z"),
                        RestrictedFavoritePropertyResponse.unavailable())),
                new PageMeta(0, 12, 1, 1, false, false));
        when(service.list(any(Authentication.class), eq(0), eq(12), nullable(String.class)))
                .thenReturn(body);

        mockMvc.perform(get("/api/v1/favorites").with(buyer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].propertyId").value(PROPERTY_ID.toString()))
                .andExpect(jsonPath("$.data[0].property.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data[0].property.placeholder")
                        .value("Property no longer available"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(12))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void explicitlyBlankSortIsRejectedInsteadOfUsingTheDefault() throws Exception {
        when(service.list(any(Authentication.class), eq(0), eq(12), eq("")))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST));

        mockMvc.perform(get("/api/v1/favorites").param("sort", "").with(buyer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void literalBatchStatusRouteTakesPrecedenceOverVariableStatusRoute() throws Exception {
        String ids = PROPERTY_ID + "," + OTHER_PROPERTY_ID;
        when(service.batchStatus(any(Authentication.class), eq(ids)))
                .thenReturn(new BatchFavoriteStatusResponse(List.of(
                        new FavoriteStatusResponse(PROPERTY_ID, true),
                        new FavoriteStatusResponse(OTHER_PROPERTY_ID, false))));

        mockMvc.perform(get("/api/v1/favorites/status")
                        .param("propertyIds", ids)
                        .with(buyer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.favorites[0].propertyId").value(PROPERTY_ID.toString()))
                .andExpect(jsonPath("$.data.favorites[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.favorites[1].isFavorite").value(false));

        verify(service).batchStatus(any(Authentication.class), eq(ids));
        verify(service, never()).status(any(Authentication.class), any(UUID.class));
    }

    @Test
    void individualStatusReturnsAuthoritativeState() throws Exception {
        when(service.status(any(Authentication.class), eq(PROPERTY_ID)))
                .thenReturn(new FavoriteStatusResponse(PROPERTY_ID, false));

        mockMvc.perform(get("/api/v1/favorites/" + PROPERTY_ID + "/status").with(buyer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.propertyId").value(PROPERTY_ID.toString()))
                .andExpect(jsonPath("$.data.isFavorite").value(false));
    }

    @Test
    void serviceValidationErrorsUseTheStandardErrorEnvelope() throws Exception {
        when(service.batchStatus(any(Authentication.class), nullable(String.class)))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST));

        mockMvc.perform(get("/api/v1/favorites/status").with(buyer()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void nonBuyerRoleIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/favorites").with(agent()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/favorites/" + PROPERTY_ID)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(agent())
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any(), any(Integer.class), any(Integer.class), any());
        verify(service, never()).add(any(), any(UUID.class));
    }

    @Test
    void unauthenticatedReadIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verify(service, never()).list(any(), any(Integer.class), any(Integer.class), any());
    }

    @Test
    void mutationWithoutOriginOrCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/favorites/" + PROPERTY_ID)
                        .with(buyer())
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(service, never()).add(any(), any(UUID.class));
    }

    @Test
    void controllerContentTypeIsJson() throws Exception {
        when(service.status(any(Authentication.class), eq(PROPERTY_ID)))
                .thenReturn(new FavoriteStatusResponse(PROPERTY_ID, true));

        mockMvc.perform(get("/api/v1/favorites/" + PROPERTY_ID + "/status").with(buyer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
