package com.estatevista.property;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Stage 5.2 HTTP-boundary verification for the approved public discovery rate
 * limits. Counters remain in PostgreSQL and are keyed by the socket address;
 * forwarded headers are never trusted by {@link PropertyRateLimitFilter}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.public-search.max-attempts=2",
        "app.rate-limit.public-search.window-seconds=60",
        "app.rate-limit.property-detail.max-attempts=2",
        "app.rate-limit.property-detail.window-seconds=60"
})
class PublicDiscoveryRateLimitIntegrationTest {

    private static final String LIMITED_IP = "192.0.2.55";
    private static final String OTHER_IP = "192.0.2.56";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    @AfterEach
    void clearCounters() {
        for (String ip : new String[] {LIMITED_IP, OTHER_IP}) {
            rateLimitService.reset(RateLimitService.SCOPE_PUBLIC_SEARCH, "ip:" + ip);
            rateLimitService.reset(RateLimitService.SCOPE_PROPERTY_DETAIL, "ip:" + ip);
        }
    }

    @Test
    void publicSearchAndReferenceEndpointsShareAnIpScopedLimit() throws Exception {
        mockMvc.perform(fromIp(get("/api/v1/properties"), LIMITED_IP))
                .andExpect(status().isOk());
        mockMvc.perform(fromIp(get("/api/v1/properties/recent"), LIMITED_IP))
                .andExpect(status().isOk());

        // The third request reaches a different reference endpoint, proving the
        // limit is scoped to public discovery rather than a single route.
        expectRateLimited(get("/api/v1/amenities"), LIMITED_IP);

        // A different socket address has an independent counter.
        mockMvc.perform(fromIp(get("/api/v1/property-categories"), OTHER_IP))
                .andExpect(status().isOk());

        // The database-backed counter can be explicitly cleared for the identity.
        rateLimitService.reset(RateLimitService.SCOPE_PUBLIC_SEARCH, "ip:" + LIMITED_IP);
        mockMvc.perform(fromIp(get("/api/v1/properties"), LIMITED_IP))
                .andExpect(status().isOk());
    }

    @Test
    void propertyDetailUsesASeparateIpScopedLimitAndStandardErrorEnvelope() throws Exception {
        UUID missingId = UUID.randomUUID();
        mockMvc.perform(fromIp(get("/api/v1/properties/{id}", missingId), LIMITED_IP))
                .andExpect(status().isNotFound());
        mockMvc.perform(fromIp(get("/api/v1/properties/{id}/images", missingId), LIMITED_IP))
                .andExpect(status().isNotFound());

        expectRateLimited(get("/api/v1/properties/{id}", missingId), LIMITED_IP);

        // Public search remains separately counted for the same address.
        mockMvc.perform(fromIp(get("/api/v1/properties"), LIMITED_IP))
                .andExpect(status().isOk());
    }

    private void expectRateLimited(MockHttpServletRequestBuilder request, String ip)
            throws Exception {
        mockMvc.perform(fromIp(request, ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    private static MockHttpServletRequestBuilder fromIp(
            MockHttpServletRequestBuilder request, String ip) {
        return request.with(servletRequest -> {
            servletRequest.setRemoteAddr(ip);
            return servletRequest;
        });
    }
}
