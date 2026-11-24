package com.estatevista.favorite;

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

/** Filter-level verification of the overlapping Stage 6.2 favorite buckets. */
class FavoriteRateLimitFilterTest {

    private static final String BUYER = "favorite-buyer-id";
    private static final String SOCKET_ADDRESS = "203.0.113.7";
    private static final UUID PROPERTY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

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
    void everyFavoriteReadUsesTheSharedAuthenticatedUserBucket() throws Exception {
        for (String path : List.of(
                "/api/v1/favorites",
                "/api/v1/favorites/status",
                "/api/v1/favorites/" + PROPERTY_ID + "/status")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            if (path.endsWith("/status") && !path.contains(PROPERTY_ID.toString())) {
                request.setQueryString("propertyIds=" + PROPERTY_ID);
            }
            request.setRemoteAddr(SOCKET_ADDRESS);
            request.addHeader("X-User-Id", "spoofed-user");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(rateLimitService, times(3)).acquire(
                RateLimitService.SCOPE_AUTHENTICATED_API, "user:" + BUYER);
    }

    @Test
    void favoriteMutationsApplyBothBucketsAtomicallyWithServerDerivedIdentity() throws Exception {
        MockHttpServletRequest add = new MockHttpServletRequest(
                "POST", "/api/v1/favorites/" + PROPERTY_ID);
        add.setRemoteAddr(SOCKET_ADDRESS);
        add.addHeader("X-User-Id", "spoofed-user");
        MockHttpServletRequest remove = new MockHttpServletRequest(
                "DELETE", "/api/v1/favorites/" + PROPERTY_ID);
        remove.setRemoteAddr(SOCKET_ADDRESS);
        remove.addHeader("X-Forwarded-For", "198.51.100.9");

        filter.doFilter(add, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(remove, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService, times(2)).acquire(
                eq(List.of(RateLimitService.SCOPE_AUTHENTICATED_API,
                        RateLimitService.SCOPE_FAVORITE_MUTATION)),
                eq("user:" + BUYER));
    }

    @Test
    void rejectionUsesGenericEnvelopeNoStoreAndLongestRetryAfter() throws Exception {
        doThrow(new RateLimitExceededException(120))
                .when(rateLimitService).acquire(eq(List.of(
                        RateLimitService.SCOPE_AUTHENTICATED_API,
                        RateLimitService.SCOPE_FAVORITE_MUTATION)), anyString());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api/v1/favorites/" + PROPERTY_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("120");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("path").asText())
                .isEqualTo("/api/v1/favorites/" + PROPERTY_ID);
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
