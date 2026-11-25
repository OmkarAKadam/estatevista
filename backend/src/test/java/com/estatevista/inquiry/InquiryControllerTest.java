package com.estatevista.inquiry;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.estatevista.inquiry.dto.InquirySubmissionResponse;
import com.estatevista.inquiry.dto.InquirySubmitRequest;
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

/** HTTP contract tests for the approved Stage 6.4 inquiry routes. */
@WebMvcTest(controllers = InquiryController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5500",
        "app.websocket.endpoint=/ws",
        "app.websocket.allowed-origins=http://localhost:5500"
})
class InquiryControllerTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final UUID PROPERTY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INQUIRY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUYER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InquiryService service;
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

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    @Test
    void submitReturnsCreatedEnvelopeAndNoStore() throws Exception {
        when(service.submit(any(Authentication.class), eq(PROPERTY_ID), any(InquirySubmitRequest.class)))
                .thenReturn(new InquirySubmissionResponse(
                        INQUIRY_ID, PROPERTY_ID, "NEW", Instant.parse("2026-11-25T10:30:00Z")));

        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Tour\",\"message\":\"Please contact me\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Inquiry submitted successfully"))
                .andExpect(jsonPath("$.data.id").value(INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.data.propertyId").value(PROPERTY_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("NEW"));
    }

    @Test
    void submitRejectsUnknownFields() throws Exception {
        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"status\":\"NEW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(service, never()).submit(any(), any(), any());
    }

    @Test
    void submitMapsConflictTo409() throws Exception {
        when(service.submit(any(Authentication.class), eq(PROPERTY_ID), any(InquirySubmitRequest.class)))
                .thenThrow(new ApiException(ErrorCode.CONFLICT,
                        "An active inquiry already exists for this property"));

        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void listOwnReturnsPagedEnvelopeAndNoStore() throws Exception {
        PagedResponse<InquiryListItemResponse> body = new PagedResponse<>(true,
                List.of(new InquiryListItemResponse(
                        INQUIRY_ID, PROPERTY_ID, "Title", BUYER_ID, AGENT_ID,
                        "Subject", "NEW", null,
                        Instant.parse("2026-11-25T10:30:00Z"),
                        Instant.parse("2026-11-25T10:30:00Z"))),
                new PageMeta(0, 20, 1, 1, false, false));
        when(service.listOwn(any(Authentication.class), eq(0), eq(20), nullable(String.class), nullable(String.class)))
                .thenReturn(body);

        mockMvc.perform(get("/api/v1/inquiries/me").with(buyer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.data[0].message").doesNotExist())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void detailReturnsEnvelopeAndNoStoreForAuthorizedRoles() throws Exception {
        when(service.getDetail(any(Authentication.class), eq(INQUIRY_ID)))
                .thenReturn(new InquiryDetailResponse(
                        INQUIRY_ID, PROPERTY_ID, "Title", BUYER_ID, AGENT_ID,
                        "Subject", "Message", "NEW", null,
                        Instant.parse("2026-11-25T10:30:00Z"),
                        Instant.parse("2026-11-25T10:30:00Z")));

        mockMvc.perform(get("/api/v1/inquiries/" + INQUIRY_ID).with(buyer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("Message"));

        mockMvc.perform(get("/api/v1/inquiries/" + INQUIRY_ID).with(agent()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inquiries/" + INQUIRY_ID).with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void unauthorizedAndRoleViolationsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/inquiries/me").with(agent()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .with(agent())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).listOwn(any(), any(int.class), any(int.class), any(), any());
    }

    @Test
    void submitRequiresOriginAndCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/properties/" + PROPERTY_ID + "/inquiries")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .with(buyer())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).submit(any(), any(), any());
    }

    @Test
    void detailNotFoundIsGeneric() throws Exception {
        when(service.getDetail(any(Authentication.class), eq(INQUIRY_ID)))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage()));

        mockMvc.perform(get("/api/v1/inquiries/" + INQUIRY_ID).with(buyer()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.NOT_FOUND.defaultMessage()));
    }
}
