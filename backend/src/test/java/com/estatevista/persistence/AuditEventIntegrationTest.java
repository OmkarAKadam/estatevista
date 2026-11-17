package com.estatevista.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.logging.CorrelationIdFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
}
