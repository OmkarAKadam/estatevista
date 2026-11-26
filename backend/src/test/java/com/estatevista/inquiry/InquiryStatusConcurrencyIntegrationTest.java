package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
import com.estatevista.inquiry.dto.InquirySubmitRequest;
import com.estatevista.inquiry.entity.Inquiry;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.inquiry.service.InquiryService;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/**
 * Stage 6.5 real-PostgreSQL concurrency verification.
 *
 * <p>Each mutation scenario holds a PostgreSQL row lock in an independent
 * transaction until both PATCH service calls are demonstrably in flight and
 * waiting on that row. {@code pg_stat_activity} and {@code pg_locks} are used
 * as the overlap barrier, so this is not a merely sequential test.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquiryStatusConcurrencyIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final long BARRIER_TIMEOUT_SECONDS = 15;

    @Autowired private InquiryService service;
    @Autowired private InquiryRepository inquiries;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final List<UUID> createdInquiryIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    private User buyer;
    private User agent;
    private User adminA;
    private User adminB;

    @BeforeEach
    void setUp() {
        buyer = user("BUYER", "concurrency-buyer");
        agent = user("AGENT", "concurrency-agent");
        adminA = user("ADMIN", "concurrency-admin-a");
        adminB = user("ADMIN", "concurrency-admin-b");
    }

    @AfterEach
    void cleanUp() {
        if (!createdInquiryIds.isEmpty()) {
            jdbc.update("delete from audit_logs where entity_id in ("
                    + placeholders(createdInquiryIds.size()) + ")", createdInquiryIds.toArray());
            jdbc.update("delete from inquiries where id in ("
                    + placeholders(createdInquiryIds.size()) + ")", createdInquiryIds.toArray());
        }
        if (!createdPropertyIds.isEmpty()) {
            jdbc.update("delete from properties where id in ("
                    + placeholders(createdPropertyIds.size()) + ")", createdPropertyIds.toArray());
        }
        if (!createdUserIds.isEmpty()) {
            jdbc.update("delete from users where id in ("
                    + placeholders(createdUserIds.size()) + ")", createdUserIds.toArray());
        }
    }

    enum Kind { SAME_TARGET, COMPETING_TARGETS, MIXED_IDEMPOTENT, FORBIDDEN_TARGET }

    static Stream<Arguments> concurrentScenarios() {
        return Stream.of(
                args("NEW", "IN_PROGRESS", "IN_PROGRESS", Kind.SAME_TARGET),
                args("NEW", "IN_PROGRESS", "CLOSED", Kind.COMPETING_TARGETS),
                args("NEW", "CLOSED", "CLOSED", Kind.SAME_TARGET),
                args("IN_PROGRESS", "RESOLVED", "RESOLVED", Kind.SAME_TARGET),
                args("IN_PROGRESS", "RESOLVED", "CLOSED", Kind.COMPETING_TARGETS),
                args("IN_PROGRESS", "CLOSED", "CLOSED", Kind.SAME_TARGET),
                args("RESOLVED", "CLOSED", "CLOSED", Kind.SAME_TARGET),
                args("RESOLVED", "CLOSED", "RESOLVED", Kind.MIXED_IDEMPOTENT),
                args("CLOSED", "CLOSED", "IN_PROGRESS", Kind.FORBIDDEN_TARGET));
    }

    private static Arguments args(String from, String first, String second, Kind kind) {
        return Arguments.of(from, first, second, kind);
    }

    @ParameterizedTest(name = "{0} concurrent PATCH {1} vs {2}")
    @MethodSource("concurrentScenarios")
    void overlappingStatusChangesAreSerializedWithoutLostUpdates(
            String from, String firstTarget, String secondTarget, Kind kind) {
        UUID id = inquiry(from);
        ageTimestamps(id, "RESOLVED".equals(from));
        var before = inquiries.findById(id).orElseThrow();
        Instant oldResolvedAt = before.getResolvedAt();
        Instant oldUpdatedAt = before.getUpdatedAt();

        List<ChangeOutcome> outcomes = runBehindInquiryLock(
                id,
                () -> change(adminA, id, firstTarget),
                () -> change(adminB, id, secondTarget));

        var after = inquiries.findById(id).orElseThrow();
        long audits = auditCount(id);

        if (kind == Kind.SAME_TARGET) {
            assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
            assertThat(after.getStatus()).isEqualTo(firstTarget);
            assertThat(audits).isEqualTo(1);
            assertTimestampChangedOnce(after, before, firstTarget, oldResolvedAt);
        } else if (kind == Kind.COMPETING_TARGETS) {
            assertThat(outcomes).hasSize(2);
            long successes = outcomes.stream().filter(o -> o.error() == null).count();
            // Serialization may produce one real transition plus one 409, or a
            // valid chained transition (e.g. NEW->IN_PROGRESS then ->CLOSED).
            // Both commit orders remain correct as long as no update is lost.
            assertThat(successes).isIn(1L, 2L);
            assertThat(audits).isEqualTo(successes);
            if (successes == 1L) {
                assertThat(statusOf(outcomes.get(0).error()) == 409
                        || statusOf(outcomes.get(1).error()) == 409).isTrue();
            }
            assertThat(after.getStatus()).isIn(firstTarget, secondTarget);
            // IN_PROGRESS -> RESOLVED -> CLOSED is a valid chained outcome and
            // legitimately leaves resolvedAt set; direct IN_PROGRESS -> CLOSED
            // must leave it null.
            String timestampKind = "CLOSED".equals(after.getStatus())
                    && after.getResolvedAt() != null ? "RESOLVED" : after.getStatus();
            assertTimestampChangedOnce(after, before, timestampKind, oldResolvedAt);
        } else if (kind == Kind.MIXED_IDEMPOTENT) {
            // One caller repeats the current status; the other closes it. The
            // idempotent caller may commit before or after the transition, but
            // exactly one real transition is audited.
            long successes = outcomes.stream().filter(o -> o.error() == null).count();
            assertThat(successes).isIn(1L, 2L);
            assertThat(audits).isEqualTo(1);
            if (successes == 1L) {
                assertThat(statusOf(outcomes.get(0).error()) == 409
                        || statusOf(outcomes.get(1).error()) == 409).isTrue();
            }
            assertThat(after.getStatus()).isEqualTo("CLOSED");
            assertTimestampChangedOnce(after, before, "CLOSED", oldResolvedAt);
        } else {
            assertThat(outcomes).extracting(ChangeOutcome::status).containsExactlyInAnyOrder("CLOSED", null);
            assertThat(statusOf(outcomes.stream()
                    .map(ChangeOutcome::error)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElseThrow())).isEqualTo(409);
            assertThat(after.getStatus()).isEqualTo("CLOSED");
            assertThat(audits).isZero();
            assertThat(after.getUpdatedAt()).isEqualTo(oldUpdatedAt);
            assertThat(after.getResolvedAt()).isEqualTo(oldResolvedAt);
        }
    }

    @Test
    void overlappingHistoricalAgentAndAdminChangesProduceOneRealTransition() {
        UUID id = inquiry("IN_PROGRESS");
        ageTimestamps(id, false);
        var before = inquiries.findById(id).orElseThrow();

        List<ChangeOutcome> outcomes = runBehindInquiryLock(
                id,
                () -> change(agent, id, "RESOLVED"),
                () -> change(adminB, id, "CLOSED"));

        var after = inquiries.findById(id).orElseThrow();
        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        assertThat(successes).isIn(1L, 2L);
        assertThat(auditCount(id)).isEqualTo(successes);
        assertThat(after.getStatus()).isIn("RESOLVED", "CLOSED");
        assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());
        if (successes == 2L) {
            // RESOLVED committed first, then CLOSED preserves resolvedAt.
            assertThat(after.getStatus()).isEqualTo("CLOSED");
            assertThat(after.getResolvedAt()).isNotNull()
                    .isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
        } else if ("RESOLVED".equals(after.getStatus())) {
            assertThat(after.getResolvedAt()).isNotNull()
                    .isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
        } else {
            assertThat(after.getResolvedAt()).isNull();
        }
    }

    @Test
    void actorDeactivationTransactionBlocksPatchAndThenDeniesIt() throws Exception {
        UUID id = inquiry("NEW");
        ageTimestamps(id, false);
        var before = inquiries.findById(id).orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            try (Connection guard = dataSource.getConnection();
                 Statement statement = guard.createStatement()) {
                guard.setAutoCommit(false);
                statement.execute("select id from users where id='" + adminA.getId() + "' for update");
                statement.execute("update users set account_status='INACTIVE' where id='" + adminA.getId() + "'");

                Future<ChangeOutcome> future = executor.submit(() -> change(adminA, id, "IN_PROGRESS"));
                awaitBlockedTransactions(1, 1, "account deactivation vs PATCH");
                guard.commit();

                ChangeOutcome outcome = future.get(20, TimeUnit.SECONDS);
                assertThat(outcome.error()).isNotNull();
                assertThat(((ApiException) outcome.error()).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        var after = inquiries.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("NEW");
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void buyerDeactivationTransactionBlocksSubmissionAndThenDeniesIt() throws Exception {
        Property target = property();

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            try (Connection guard = dataSource.getConnection();
                 Statement statement = guard.createStatement()) {
                guard.setAutoCommit(false);
                statement.execute("select id from users where id='" + buyer.getId() + "' for update");
                statement.execute("update users set account_status='INACTIVE' where id='" + buyer.getId() + "'");

                Future<ApiException> future = executor.submit(() -> {
                    try {
                        service.submit(auth(buyer, "BUYER"), target.getId(), submitRequest());
                        return null;
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                awaitBlockedTransactions(1, 1, "buyer deactivation vs inquiry submission");
                guard.commit();

                ApiException error = future.get(20, TimeUnit.SECONDS);
                assertThat(error).isNotNull();
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Long count = jdbc.queryForObject(
                "select count(*) from inquiries where buyer_id=? and property_id=?",
                Long.class, buyer.getId(), target.getId());
        assertThat(count).isZero();
    }

    private void assertTimestampChangedOnce(
            Inquiry after, Inquiry before, String finalStatus, Instant oldResolvedAt) {
        assertThat(after.getUpdatedAt()).isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
        assertThat(after.getUpdatedAt()).isNotEqualTo(before.getUpdatedAt());
        if ("RESOLVED".equals(finalStatus)) {
            assertThat(after.getResolvedAt()).isNotNull()
                    .isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
        } else if (oldResolvedAt != null) {
            assertThat(after.getResolvedAt()).isEqualTo(oldResolvedAt);
        } else {
            assertThat(after.getResolvedAt()).isNull();
        }
    }

    private List<ChangeOutcome> runBehindInquiryLock(
            UUID inquiryId, Callable<ChangeOutcome> first, Callable<ChangeOutcome> second) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            try (Connection guard = dataSource.getConnection();
                 Statement statement = guard.createStatement()) {
                guard.setAutoCommit(false);
                statement.execute("select id from inquiries where id='" + inquiryId + "' for update");

                Future<ChangeOutcome> firstFuture = executor.submit(first);
                Future<ChangeOutcome> secondFuture = executor.submit(second);
                awaitBlockedTransactions(2, 2, "PATCH vs PATCH");

                guard.commit();
                return List.of(getOutcome(firstFuture), getOutcome(secondFuture));
            }
        } catch (Exception ex) {
            return fail("overlapping PATCH transactions did not complete", ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private ChangeOutcome getOutcome(Future<ChangeOutcome> future)
            throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        return future.get(20, TimeUnit.SECONDS);
    }

    private ChangeOutcome change(User actor, UUID inquiryId, String status) {
        try {
            return new ChangeOutcome(
                    service.changeStatus(auth(actor, actor == agent ? "AGENT" : "ADMIN"),
                            inquiryId, patch(status)).status(),
                    null);
        } catch (ApiException ex) {
            return new ChangeOutcome(null, ex);
        }
    }

    private static int statusOf(Throwable error) {
        return error instanceof ApiException api ? api.getErrorCode().status().value() : -1;
    }

    private void awaitBlockedTransactions(
            int minimumWaiters, int minimumUngranted, String scenario) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BARRIER_TIMEOUT_SECONDS);
        int maxActiveWaiters = 0;
        int maxUngrantedLocks = 0;
        while (System.nanoTime() < deadline) {
            int activeWaiters = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() "
                            + "and state='active' and wait_event_type='Lock'",
                    Integer.class);
            // Row-lock waits appear primarily as ungranted transactionid locks,
            // so count all ungranted locks rather than only relation locks.
            int ungranted = jdbc.queryForObject(
                    "select count(*) from pg_locks where not granted", Integer.class);
            maxActiveWaiters = Math.max(maxActiveWaiters, activeWaiters);
            maxUngrantedLocks = Math.max(maxUngrantedLocks, ungranted);
            if (activeWaiters >= minimumWaiters && ungranted >= minimumUngranted) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for lock overlap", ex);
            }
        }
        fail("no real overlapping lock wait observed for " + scenario
                + "; pg_stat_activity waiters=" + maxActiveWaiters
                + ", pg_locks ungranted=" + maxUngrantedLocks);
    }

    private UUID inquiry(String status) {
        UUID id = UUID.randomUUID();
        assertThat(jdbc.update(
                "insert into inquiries "
                        + "(id, buyer_id, property_id, assigned_agent_id, subject, message, "
                        + "status, created_at, updated_at, resolved_at) "
                        + "values (?, ?, ?, ?, 'concurrency subject', 'concurrency message', ?, now(), now(), ?)",
                id, buyer.getId(), property().getId(), agent.getId(), status,
                "RESOLVED".equals(status)
                        ? java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)) : null)).isEqualTo(1);
        createdInquiryIds.add(id);
        return id;
    }

    private Property property() {
        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                .stream().findFirst().orElseThrow();
        Property entity = new Property();
        entity.setTitle("Concurrency property " + UUID.randomUUID());
        entity.setSlug("concurrency-" + UUID.randomUUID());
        entity.setDescription("Stage 6.5 concurrency fixture");
        entity.setCategory(category);
        entity.setSubtype(subtype);
        entity.setListingType(ListingType.SALE);
        entity.setPrice(new BigDecimal("2500000.00"));
        entity.setCurrencyCode("INR");
        entity.setAreaValue(new BigDecimal("1200.00"));
        entity.setAreaUnit("SQ_FT");
        entity.setAddressLine("1 Concurrency Road");
        entity.setLocality("Concurrency Locality");
        entity.setCity("Vadodara");
        entity.setState("Gujarat");
        entity.setCountry("India");
        entity.setPostalCode("390001");
        entity.setStatus(PropertyStatus.PUBLISHED);
        entity.setPrimaryAgent(agent);
        entity.setCreatedBy(adminA);
        entity.setPublishedAt(Instant.now());
        Property saved = properties.saveAndFlush(entity);
        createdPropertyIds.add(saved.getId());
        return saved;
    }

    private User user(String role, String label) {
        Role entity = roles.findByName(role).orElseThrow();
        User entityUser = new User();
        entityUser.setRole(entity);
        entityUser.setFirstName("Stage65");
        entityUser.setLastName(label);
        entityUser.setEmail(label + UUID.randomUUID().toString().toLowerCase(Locale.ROOT) + "@example.com");
        entityUser.setPhone("+919876543210");
        entityUser.setPasswordHash("unused");
        entityUser.setAccountStatus(AccountStatus.ACTIVE);
        User saved = users.saveAndFlush(entityUser);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private void ageTimestamps(UUID id, boolean resolved) {
        jdbc.update("update inquiries set created_at=?, updated_at=?, resolved_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                resolved ? java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)) : null,
                id);
    }

    private static InquiryStatusPatchRequest patch(String status) {
        InquiryStatusPatchRequest request = new InquiryStatusPatchRequest();
        request.setStatus(status);
        return request;
    }

    private static InquirySubmitRequest submitRequest() {
        InquirySubmitRequest request = new InquirySubmitRequest();
        request.setSubject("Concurrency submission subject");
        request.setMessage("Concurrency submission message");
        return request;
    }

    private static Authentication auth(User actor, String role) {
        return new UsernamePasswordAuthenticationToken(actor.getId().toString(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private long auditCount(UUID inquiryId) {
        return jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id=? and action=?",
                Long.class, inquiryId, AuditEventService.ACTION_INQUIRY_STATUS_CHANGED);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record ChangeOutcome(String status, Throwable error) {}
}
