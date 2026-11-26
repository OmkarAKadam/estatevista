package com.estatevista.inquiry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.estatevista.inquiry.controller.InquiryController;
import com.estatevista.inquiry.dto.InquiryDetailResponse;
import com.estatevista.inquiry.dto.InquiryListItemResponse;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
import com.estatevista.inquiry.service.InquiryService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP contract and security tests for the approved Stage 6.5 management routes. */
@WebMvcTest(controllers = InquiryController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class InquiryManagementControllerTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final UUID INQUIRY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUYER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ADMIN_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PROPERTY_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private InquiryService service;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private AuditEventService auditEventService;

    private static RequestPostProcessor buyer() {
        return user(BUYER_ID.toString()).roles("BUYER");
    }

    private static RequestPostProcessor agent() {
        return user(AGENT_ID.toString()).roles("AGENT");
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_ID.toString()).roles("ADMIN");
    }

    private static InquiryDetailResponse detail(String status) {
        return new InquiryDetailResponse(INQUIRY_ID, PROPERTY_ID, "Property",
                BUYER_ID, AGENT_ID, "subject", "message", status, null,
                Instant.parse("2026-11-25T10:00:00Z"), Instant.parse("2026-11-25T10:00:00Z"));
    }

    @Test
    void agentCollectionReturnsPagedEnvelopeAndNoStore() throws Exception {
        when(service.listAssigned(any(Authentication.class), anyInt(), anyInt(),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new PagedResponse<>(true, List.of(), new PageMeta(0, 20, 0, 0, false, false)));

        mockMvc.perform(get("/api/v1/agents/me/inquiries").with(agent()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void adminCollectionDeniesBuyerAndAgentButAllowsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inquiries").with(buyer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/inquiries").with(agent()))
                .andExpect(status().isForbidden());
        when(service.listForAdmin(any(Authentication.class), anyInt(), anyInt(),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new PagedResponse<>(true, List.of(), new PageMeta(0, 20, 0, 0, false, false)));
        mockMvc.perform(get("/api/v1/admin/inquiries").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void statusPatchReturnsUpdatedDetailForAgentAndAdmin() throws Exception {
        when(service.changeStatus(any(Authentication.class), eq(INQUIRY_ID),
                any(InquiryStatusPatchRequest.class))).thenReturn(detail("IN_PROGRESS"));

        for (RequestPostProcessor actor : List.of(agent(), admin())) {
            mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .with(actor)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"IN_PROGRESS\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                            org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        }
    }

    @Test
    void buyerAndAnonymousStatusPatchAreDenied() throws Exception {
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).changeStatus(any(), any(), any());
    }

    @Test
    void statusPatchEnforcesCsrfAndTrustedOriginOrReferer() throws Exception {
        when(service.changeStatus(any(Authentication.class), eq(INQUIRY_ID),
                any(InquiryStatusPatchRequest.class))).thenReturn(detail("CLOSED"));

        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.REFERER, ORIGIN + "/agents/inquiries")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.REFERER, "https://untrusted.example/page")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusPatchRejectsUnknownFieldsInvalidStatusAndMalformedUuid() throws Exception {
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"resolvedAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"resolvedAt\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REOPENED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/inquiries/not-a-uuid/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).changeStatus(any(), any(), any());
    }

    @Test
    void statusPatchMapsServiceConflictTo409() throws Exception {
        when(service.changeStatus(any(Authentication.class), eq(INQUIRY_ID),
                any(InquiryStatusPatchRequest.class)))
                .thenThrow(new ApiException(ErrorCode.CONFLICT, "conflict"));
        mockMvc.perform(patch("/api/v1/inquiries/" + INQUIRY_ID + "/status")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(agent())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isConflict());
    }
}
