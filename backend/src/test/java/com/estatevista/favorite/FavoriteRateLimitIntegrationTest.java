package com.estatevista.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.RateLimitExceededException;
import com.estatevista.security.RateLimitProperties;
import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Shared-bucket verification against real PostgreSQL rate-limit counters. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class FavoriteRateLimitIntegrationTest {

    private final String identity = "user:" + UUID.randomUUID();
    private final RateLimitProperties properties = favoriteProperties();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService(
                jdbcTemplate, properties, org.mockito.Mockito.mock(AuditEventService.class));
        service.reset(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        service.reset(RateLimitService.SCOPE_FAVORITE_MUTATION, identity);
    }

    @AfterEach
    void cleanUp() {
        service.reset(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        service.reset(RateLimitService.SCOPE_FAVORITE_MUTATION, identity);
    }

    @Test
    void overlappingBucketsConsumeTogetherAndReportLongestBlock() {
        List<String> mutationScopes = List.of(
                RateLimitService.SCOPE_AUTHENTICATED_API,
                RateLimitService.SCOPE_FAVORITE_MUTATION);

        service.acquire(mutationScopes, identity);

        assertThatThrownBy(() -> service.acquire(mutationScopes, identity))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception ->
                        assertThat(exception.getRetryAfterSeconds()).isEqualTo(120L));

        // The rejected mutation consumed neither bucket: the shared bucket still
        // accepts exactly the two remaining attempts of its 3-request threshold.
        service.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        service.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, identity);
        assertThatThrownBy(() ->
                service.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, identity))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception ->
                        assertThat(exception.getRetryAfterSeconds()).isEqualTo(60L));
    }

    private static RateLimitProperties favoriteProperties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.getAuthenticatedApi().setMaxAttempts(3);
        properties.getAuthenticatedApi().setWindowSeconds(60);
        properties.getFavoriteMutation().setMaxAttempts(1);
        properties.getFavoriteMutation().setWindowSeconds(120);
        return properties;
    }
}
