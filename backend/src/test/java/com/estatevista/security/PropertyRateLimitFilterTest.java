package com.estatevista.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import com.estatevista.common.exception.RateLimitExceededException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Deterministic filter-scope tests. No database, server, or real client is
 * required; the limiter is stubbed so exact scope and actor keys are asserted.
 */
class PropertyRateLimitFilterTest {

    private static final String SOCKET_ADDRESS = "203.0.113.7";
    private static final String SPOOFED_FORWARDED_ADDRESS = "198.51.100.9";

    private final RateLimitService rateLimitService =
            org.mockito.Mockito.mock(RateLimitService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PropertyRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PropertyRateLimitFilter(rateLimitService, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publicSearchUsesSocketAddressAndNeverTrustsForwardedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/properties");
        request.setRemoteAddr(SOCKET_ADDRESS);
        request.addHeader("X-Forwarded-For", SPOOFED_FORWARDED_ADDRESS);
        doThrow(new RateLimitExceededException(60)).when(rateLimitService).acquire(
                RateLimitService.SCOPE_PUBLIC_SEARCH, "ip:" + SOCKET_ADDRESS);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("path").asText()).isEqualTo("/api/v1/properties");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void propertyDetailUsesItsOwnScopeForTheSameClientAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/properties/11111111-1111-1111-1111-111111111111");
        request.setRemoteAddr(SOCKET_ADDRESS);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        verify(rateLimitService).acquire(
                RateLimitService.SCOPE_PROPERTY_DETAIL, "ip:" + SOCKET_ADDRESS);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void agentMutationIsKeyedByServerSidePrincipal() throws Exception {
        authenticate("agent-user-id", "ROLE_AGENT");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH", "/api/v1/properties/11111111-1111-1111-1111-111111111111/status");
        request.setRemoteAddr(SOCKET_ADDRESS);
        request.addHeader("X-User-Id", "spoofed-user");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).acquire(
                RateLimitService.SCOPE_AUTHENTICATED_API, "user:agent-user-id");
    }

    @Test
    void adminMutationUsesAdminScopeAndAuthenticatedUserKey() throws Exception {
        authenticate("admin-user-id", "ROLE_ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/properties");
        request.setRemoteAddr(SOCKET_ADDRESS);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).acquire(
                RateLimitService.SCOPE_ADMIN_MUTATION, "user:admin-user-id");
    }

    @Test
    void imageUploadIsLeftToTheExistingServiceLevelScope() throws Exception {
        authenticate("agent-user-id", "ROLE_AGENT");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/properties/11111111-1111-1111-1111-111111111111/images");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(rateLimitService);
    }

    @Test
    void requestContinuesWhenTheLimiterAllowsIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/properties");
        request.setRemoteAddr(SOCKET_ADDRESS);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static void authenticate(String principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(role))));
    }
}
