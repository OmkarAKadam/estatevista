package com.estatevista.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.logging.CorrelationIdFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Real-PostgreSQL verification of the Stage 3.6 audit writer (D-11).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AuditEventIntegrationTest {

    private static final String REQUEST_ID = "stage36-audit-" + UUID.randomUUID();

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE request_id = ?", REQUEST_ID);
    }

    @Test
    void writesRedactedAuditRowWithCorrelationIdAndClientAddress() {
        MDC.put(CorrelationIdFilter.MDC_KEY, REQUEST_ID);

        auditEventService.recordLoginFailure();
        auditEventService.recordLoginSuccess(UUID.randomUUID());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, entity_type, after_data, request_id, ip_address "
                        + "FROM audit_logs WHERE request_id = ? ORDER BY created_at, action",
                REQUEST_ID);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("action")).isEqualTo(AuditEventService.ACTION_LOGIN_FAILURE);
        assertThat(rows.get(0).get("entity_type")).isEqualTo("AUTHENTICATION");
        assertThat(rows.get(1).get("action")).isEqualTo(AuditEventService.ACTION_LOGIN_SUCCESS);
        for (Map<String, Object> row : rows) {
            String payload = String.valueOf(row.get("after_data"));
            assertThat(payload).contains("outcome");
            assertThat(payload.toLowerCase()).doesNotContain("password", "token", "session");
        }
    }

    @Test
    void securityEventsArePersistedOnceWithCorrelationIdAndMinimalMetadata() {
        MDC.put(CorrelationIdFilter.MDC_KEY, REQUEST_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader(CorrelationIdFilter.HEADER, REQUEST_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // Each logical event is invoked twice; the request-scoped guard must
        // prevent duplicate rows without changing the primary response path.
        auditEventService.recordRateLimitRejected("login");
        auditEventService.recordRateLimitRejected("login");
        auditEventService.recordCsrfFailure(request);
        auditEventService.recordCsrfFailure(request);
        auditEventService.recordAccessDenied(request);
        auditEventService.recordAccessDenied(request);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, after_data, request_id, ip_address FROM audit_logs "
                        + "WHERE request_id = ? ORDER BY action",
                REQUEST_ID);

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.get("action")))
                .containsExactly(
                        AuditEventService.ACTION_ACCESS_DENIED,
                        AuditEventService.ACTION_CSRF_FAILURE,
                        AuditEventService.ACTION_RATE_LIMIT_REJECTED);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("request_id")).isEqualTo(REQUEST_ID);
            assertThat(row.get("ip_address")).isNotNull();
            String payload = String.valueOf(row.get("after_data")).toLowerCase();
            assertThat(payload)
                    .contains("outcome")
                    .contains("reason")
                    .doesNotContain("password", "token", "session", "cookie", "authorization");
        }
        assertThat(rows.stream().map(row -> String.valueOf(row.get("after_data")))
                .map(payload -> payload.contains("ANONYMOUS")))
                .allMatch(Boolean::booleanValue);
        assertThat(rows.stream()
                .map(row -> String.valueOf(row.get("after_data")))
                .collect(Collectors.joining()))
                .doesNotContain("203.0.113.10");
    }
}
