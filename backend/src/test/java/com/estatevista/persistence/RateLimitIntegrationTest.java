package com.estatevista.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.exception.RateLimitExceededException;
import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real-PostgreSQL verification that Stage 3.6 rate limiting is shared through
 * the database rather than held in application memory.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login.max-attempts=3",
        "app.rate-limit.login.window-seconds=60",
        "app.rate-limit.registration.max-attempts=2",
        "app.rate-limit.registration.window-seconds=60",
        "app.rate-limit.public-search.max-attempts=2",
        "app.rate-limit.public-search.window-seconds=60",
        "app.rate-limit.property-detail.max-attempts=3",
        "app.rate-limit.property-detail.window-seconds=60",
        "app.rate-limit.authenticated-api.max-attempts=2",
        "app.rate-limit.authenticated-api.window-seconds=60",
        "app.rate-limit.admin-mutation.max-attempts=2",
        "app.rate-limit.admin-mutation.window-seconds=60"
})
class RateLimitIntegrationTest {

    private static final String LOGIN_IDENTITY = "stage36-" + UUID.randomUUID() + "|127.0.0.1";
    private static final String REGISTRATION_IDENTITY = "stage36-" + UUID.randomUUID();
    private static final String SEARCH_IDENTITY = "stage4-search-" + UUID.randomUUID();
    private static final String DETAIL_IDENTITY = "stage4-detail-" + UUID.randomUUID();
    private static final String OTHER_SEARCH_IDENTITY = "stage4-other-" + UUID.randomUUID();
    private static final String USER_IDENTITY = "stage4-user-" + UUID.randomUUID();
    private static final String ADMIN_IDENTITY = "stage4-admin-" + UUID.randomUUID();

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        rateLimitService.reset(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_REGISTRATION, REGISTRATION_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_PUBLIC_SEARCH, OTHER_SEARCH_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_PROPERTY_DETAIL, DETAIL_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_AUTHENTICATED_API, USER_IDENTITY);
        rateLimitService.reset(RateLimitService.SCOPE_ADMIN_MUTATION, ADMIN_IDENTITY);
    }

    @Test
    void loginAttemptsAreCountedAcrossTransactionsAndClearedAfterSuccess() {
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);

        assertThatThrownBy(() ->
                rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        rateLimitService.reset(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);
    }

    @Test
    void registrationLimitUsesASeparateScope() {
        rateLimitService.acquire(RateLimitService.SCOPE_REGISTRATION, REGISTRATION_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_REGISTRATION, REGISTRATION_IDENTITY);

        assertThatThrownBy(() -> rateLimitService.acquire(
                RateLimitService.SCOPE_REGISTRATION, REGISTRATION_IDENTITY))
                .isInstanceOf(ApiException.class);
        assertThatCodeLoginScopeStillAllowsAttempts();
    }

    @Test
    void propertyScopesEnforceIsolateAndRecoverWithDatabaseBackedCounters() {
        rateLimitService.acquire(RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY);

        assertThatThrownBy(() -> rateLimitService.acquire(
                RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(60L);
                });

        // A different scope and a different actor keep independent counters.
        rateLimitService.acquire(RateLimitService.SCOPE_PROPERTY_DETAIL, SEARCH_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_PUBLIC_SEARCH, OTHER_SEARCH_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_AUTHENTICATED_API, USER_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_ADMIN_MUTATION, ADMIN_IDENTITY);

        rateLimitService.reset(RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY);
        rateLimitService.acquire(RateLimitService.SCOPE_PUBLIC_SEARCH, SEARCH_IDENTITY);
    }

    @Test
    void storedKeysAreDigestsAndNeverContainTheRawIdentity() {
        rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, LOGIN_IDENTITY);

        Long rawIdentityRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_rate_limit_events WHERE attempt_key LIKE ?",
                Long.class,
                "%stage36%");
        assertThat(rawIdentityRows).isZero();
    }

    private void assertThatCodeLoginScopeStillAllowsAttempts() {
        String otherIdentity = "stage36-other-" + UUID.randomUUID();
        try {
            rateLimitService.acquire(RateLimitService.SCOPE_LOGIN, otherIdentity);
        } finally {
            rateLimitService.reset(RateLimitService.SCOPE_LOGIN, otherIdentity);
        }
    }
}
