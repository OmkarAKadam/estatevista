package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import com.estatevista.common.exception.RateLimitExceededException;
import com.estatevista.security.PropertyRateLimitFilter;
import com.estatevista.security.RateLimitService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Filter-level verification of Stage 6.4 inquiry rate-limit scopes. */
class InquiryRateLimitFilterTest {

    private static final String BUYER = "inquiry-buyer-id";
    private static final String SOCKET_ADDRESS = "203.0.113.7";
    private static final UUID PROPERTY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INQUIRY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final RateLimitService rateLimitService =
            org.mockito.Mockito.mock(RateLimitService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PropertyRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PropertyRateLimitFilter(rateLimitService, objectMapper);
        SecurityContextHolder.clearContext();
        authenticateBuyer();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inquiryReadsUseOnlySharedAuthenticatedUserBucket() throws Exception {
        MockHttpServletRequest list = new MockHttpServletRequest("GET", "/api/v1/inquiries/me");
        list.setRemoteAddr(SOCKET_ADDRESS);
        list.addHeader("X-User-Id", "spoofed-user");
        MockHttpServletRequest detail = new MockHttpServletRequest(
                "GET", "/api/v1/inquiries/" + INQUIRY_ID);
        detail.setRemoteAddr(SOCKET_ADDRESS);
        detail.addHeader("X-Forwarded-For", "198.51.100.9");

        filter.doFilter(list, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(detail, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService, times(2)).acquire(
                RateLimitService.SCOPE_AUTHENTICATED_API, "user:" + BUYER);
    }

    @Test
    void inquirySubmissionAppliesAllThreeBucketsAtomically() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/properties/" + PROPERTY_ID + "/inquiries");
        request.setRemoteAddr(SOCKET_ADDRESS);
        request.addHeader("X-User-Id", "spoofed-user");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).acquire(
                eq(List.of(RateLimitService.SCOPE_AUTHENTICATED_API,
                        RateLimitService.SCOPE_INQUIRY_SUBMISSION_BURST,
                        RateLimitService.SCOPE_INQUIRY_SUBMISSION_SUSTAINED)),
                eq("user:" + BUYER));
    }

    @Test
    void rejectionUsesGenericEnvelopeNoStoreAndLongestRetryAfter() throws Exception {
        doThrow(new RateLimitExceededException(3600))
                .when(rateLimitService).acquire(eq(List.of(
                        RateLimitService.SCOPE_AUTHENTICATED_API,
                        RateLimitService.SCOPE_INQUIRY_SUBMISSION_BURST,
                        RateLimitService.SCOPE_INQUIRY_SUBMISSION_SUSTAINED)), anyString());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/properties/" + PROPERTY_ID + "/inquiries");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("path").asText())
                .isEqualTo("/api/v1/properties/" + PROPERTY_ID + "/inquiries");
        assertThat(chain.getRequest()).isNull();
    }

    private static void authenticateBuyer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        BUYER,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_BUYER"))));
    }
}
