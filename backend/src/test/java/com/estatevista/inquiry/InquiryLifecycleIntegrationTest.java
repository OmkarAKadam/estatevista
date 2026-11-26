package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.inquiry.dto.InquiryDetailResponse;
import com.estatevista.inquiry.dto.InquiryListItemResponse;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** Stage 6.5 lifecycle, authorization, audit and collection verification on real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquiryLifecycleIntegrationTest {

    private static final String SENTINEL_SUBJECT = "SENTINEL-SUBJECT-DO-NOT-AUDIT";
    private static final String SENTINEL_MESSAGE = "SENTINEL-MESSAGE-DO-NOT-AUDIT";
    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private InquiryService service;
    @Autowired private InquiryRepository inquiries;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdInquiryIds = new ArrayList<>();

    private User buyer;
    private User otherBuyer;
    private User historicalAgent;
    private User newAgent;
    private User unrelatedAgent;
    private User admin;

    @BeforeEach
    void setUp() {
        buyer = user("BUYER", "lifecycle-buyer");
        otherBuyer = user("BUYER", "lifecycle-other-buyer");
        historicalAgent = user("AGENT", "lifecycle-historical-agent");
        newAgent = user("AGENT", "lifecycle-new-agent");
        unrelatedAgent = user("AGENT", "lifecycle-unrelated-agent");
        admin = user("ADMIN", "lifecycle-admin");
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : createdInquiryIds) {
            jdbc.update("delete from audit_logs where entity_id=?", id);
        }
        if (!createdInquiryIds.isEmpty()) {
            jdbc.update("delete from inquiries where id in (" + placeholders(createdInquiryIds.size()) + ")",
                    createdInquiryIds.toArray());
        }
        if (!createdPropertyIds.isEmpty()) {
            jdbc.update("delete from properties where id in (" + placeholders(createdPropertyIds.size()) + ")",
                    createdPropertyIds.toArray());
        }
        if (!createdUserIds.isEmpty()) {
            jdbc.update("delete from users where id in (" + placeholders(createdUserIds.size()) + ")",
                    createdUserIds.toArray());
        }
    }

    static Stream<Arguments> transitionMatrix() {
        return Stream.of(
                args("NEW", "NEW", "idempotent", null),
                args("NEW", "IN_PROGRESS", "transition", null),
                args("NEW", "RESOLVED", "forbidden", null),
                args("NEW", "CLOSED", "transition", null),
                args("IN_PROGRESS", "NEW", "forbidden", null),
                args("IN_PROGRESS", "IN_PROGRESS", "idempotent", null),
                args("IN_PROGRESS", "RESOLVED", "transition", "set"),
                args("IN_PROGRESS", "CLOSED", "transition", null),
                args("RESOLVED", "NEW", "forbidden", "preserve"),
                args("RESOLVED", "IN_PROGRESS", "forbidden", "preserve"),
                args("RESOLVED", "RESOLVED", "idempotent", "preserve"),
                args("RESOLVED", "CLOSED", "transition", "preserve"),
                args("CLOSED", "NEW", "forbidden", null),
                args("CLOSED", "IN_PROGRESS", "forbidden", null),
                args("CLOSED", "RESOLVED", "forbidden", null),
                args("CLOSED", "CLOSED", "idempotent", null));
    }

    private static Arguments args(String from, String to, String kind, String resolvedRule) {
        return Arguments.of(from, to, kind, resolvedRule);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("transitionMatrix")
    void completeTransitionMatrix(
            String from, String to, String kind, String resolvedRule) {
        UUID inquiryId = inquiry(from, buyer, historicalAgent, property(historicalAgent));
        ageTimestamps(inquiryId, "RESOLVED".equals(from));
        boolean realTransition = "transition".equals(kind);

        InquiryDetailResponse response;
        if ("forbidden".equals(kind)) {
            assertThatThrownBy(() -> service.changeStatus(auth(admin.getId(), "ADMIN"), inquiryId, patch(to)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode().status().value())
                            .isEqualTo(409));
            assertThat(auditCount(inquiryId)).isZero();
        } else {
            response = service.changeStatus(auth(admin.getId(), "ADMIN"), inquiryId, patch(to));
            assertThat(response.status()).isEqualTo(to);
            assertThat(auditCount(inquiryId)).isEqualTo(realTransition ? 1 : 0);
        }

        var row = inquiries.findById(inquiryId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("forbidden".equals(kind) ? from : to);
        assertThat(row.getCreatedAt()).isBefore(Instant.now().minus(90, ChronoUnit.MINUTES));
        if (realTransition) {
            assertThat(row.getUpdatedAt()).isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
            if ("set".equals(resolvedRule)) {
                assertThat(row.getResolvedAt()).isNotNull()
                        .isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
            } else if ("preserve".equals(resolvedRule)) {
                assertThat(row.getResolvedAt()).isNotNull()
                        .isBefore(Instant.now().minus(30, ChronoUnit.MINUTES));
            } else {
                assertThat(row.getResolvedAt()).isNull();
            }
            assertThat(row.getSubject()).isEqualTo(SENTINEL_SUBJECT);
            assertThat(row.getMessage()).isEqualTo(SENTINEL_MESSAGE);
            assertThat(row.getBuyer().getId()).isEqualTo(buyer.getId());
            assertThat(row.getAssignedAgent().getId()).isEqualTo(historicalAgent.getId());
        } else {
            assertThat(row.getUpdatedAt()).isBefore(Instant.now().minus(30, ChronoUnit.MINUTES));
            if ("RESOLVED".equals(from)) {
                assertThat(row.getResolvedAt()).isBefore(Instant.now().minus(30, ChronoUnit.MINUTES));
            } else {
                assertThat(row.getResolvedAt()).isNull();
            }
        }
    }

    @Test
    void idempotentRequestsDoNotMutateTimestampsOrAuditAndAreStillAuthorized() {
        UUID id = inquiry("RESOLVED", buyer, historicalAgent, property(historicalAgent));
        ageTimestamps(id, true);
        var before = inquiries.findById(id).orElseThrow();

        InquiryDetailResponse first = service.changeStatus(
                auth(historicalAgent.getId(), "AGENT"), id, patch("RESOLVED"));
        InquiryDetailResponse second = service.changeStatus(
                auth(admin.getId(), "ADMIN"), id, patch("RESOLVED"));

        var after = inquiries.findById(id).orElseThrow();
        assertThat(first.status()).isEqualTo("RESOLVED");
        assertThat(second.status()).isEqualTo("RESOLVED");
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getResolvedAt()).isEqualTo(before.getResolvedAt());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(auditCount(id)).isZero();

        assertThatThrownBy(() -> service.changeStatus(
                auth(buyer.getId(), "BUYER"), id, patch("RESOLVED")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void authorizationPrecedesLifecycleConflictAndUsesGenericNotFound() {
        UUID id = inquiry("CLOSED", buyer, historicalAgent, property(historicalAgent));
        assertThatThrownBy(() -> service.changeStatus(
                auth(unrelatedAgent.getId(), "AGENT"), id, patch("IN_PROGRESS")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.changeStatus(
                auth(unrelatedAgent.getId(), "AGENT"), UUID.randomUUID(), patch("IN_PROGRESS")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void historicalAssignmentSurvivesReassignmentAndArchival() {
        Property property = property(historicalAgent);
        UUID id = inquiry("NEW", buyer, historicalAgent, property);
        jdbc.update("update properties set primary_agent_id=?, status='ARCHIVED', archived_at=now() where id=?",
                newAgent.getId(), property.getId());

        assertThat(service.changeStatus(
                auth(historicalAgent.getId(), "AGENT"), id, patch("IN_PROGRESS")).status())
                .isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> service.changeStatus(
                auth(newAgent.getId(), "AGENT"), id, patch("CLOSED")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(inquiries.findById(id).orElseThrow().getAssignedAgent().getId())
                .isEqualTo(historicalAgent.getId());
        assertThat(service.getDetail(auth(admin.getId(), "ADMIN"), id).propertyTitle())
                .isEqualTo("Property no longer available");
    }

    @Test
    void inactiveActorIsDeniedAndReactivationRestoresHistoricalAccess() {
        UUID id = inquiry("NEW", buyer, historicalAgent, property(historicalAgent));
        jdbc.update("update users set account_status='INACTIVE' where id=?", historicalAgent.getId());
        assertThatThrownBy(() -> service.changeStatus(
                auth(historicalAgent.getId(), "AGENT"), id, patch("IN_PROGRESS")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        jdbc.update("update users set account_status='ACTIVE' where id=?", historicalAgent.getId());
        assertThat(service.changeStatus(
                auth(historicalAgent.getId(), "AGENT"), id, patch("IN_PROGRESS")).status())
                .isEqualTo("IN_PROGRESS");
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void adminCanManageInquiryAssignedToInactiveAgent() {
        UUID id = inquiry("NEW", buyer, historicalAgent, property(historicalAgent));
        jdbc.update("update users set account_status='INACTIVE' where id=?", historicalAgent.getId());
        assertThat(service.changeStatus(auth(admin.getId(), "ADMIN"), id, patch("CLOSED")).status())
                .isEqualTo("CLOSED");
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void agentCollectionContainsOnlyHistoricalAssignments() {
        Property historicalProperty = property(historicalAgent);
        Property futureProperty = property(newAgent);
        UUID mine = inquiry("NEW", buyer, historicalAgent, historicalProperty);
        UUID theirs = inquiry("NEW", otherBuyer, newAgent, futureProperty);

        var mineList = service.listAssigned(auth(historicalAgent.getId(), "AGENT"), 0, 20, "newest", null);
        assertThat(mineList.data()).extracting("id").contains(mine).doesNotContain(theirs);
        assertThat(mineList.meta().totalElements()).isEqualTo(1);

        jdbc.update("update properties set primary_agent_id=? where id=?",
                historicalAgent.getId(), futureProperty.getId());
        var afterReassignment = service.listAssigned(
                auth(historicalAgent.getId(), "AGENT"), 0, 20, "newest", null);
        assertThat(afterReassignment.data()).extracting("id").containsExactly(mine);
    }

    @Test
    void collectionsSupportPaginationSortingFiltersAndDeterministicTiebreak() {
        Property property = property(historicalAgent);
        UUID first = inquiry("NEW", buyer, historicalAgent, property);
        UUID second = inquiry("IN_PROGRESS", buyer, historicalAgent, property(historicalAgent, "in-progress"));

        // Two active rows for the same buyer/property are impossible; the second
        // fixture uses another property. Equalize created_at to exercise the
        // UUID tiebreak in both sort directions.
        jdbc.update("update inquiries set created_at=now(), status='NEW' where id in (?,?)",
                first, second);

        var newest = service.listAssigned(auth(historicalAgent.getId(), "AGENT"), 0, 1, "newest", null);
        var oldest = service.listAssigned(auth(historicalAgent.getId(), "AGENT"), 0, 1, "oldest", null);
        // PostgreSQL orders UUIDs bytewise, which is not identical to Java's
        // signed UUID.compareTo; derive the expectation from the same engine.
        UUID expectedFirst = UUID.fromString(jdbc.queryForObject(
                "select id::text from inquiries where id in (?,?) "
                        + "order by created_at desc, id desc limit 1", String.class, first, second));
        UUID expectedLast = UUID.fromString(jdbc.queryForObject(
                "select id::text from inquiries where id in (?,?) "
                        + "order by created_at asc, id asc limit 1", String.class, first, second));
        assertThat(newest.data()).extracting("id").containsExactly(expectedFirst);
        assertThat(oldest.data()).extracting("id").containsExactly(expectedLast);
        assertThat(newest.meta().totalElements()).isEqualTo(2);

        var filtered = service.listForAdmin(
                auth(admin.getId(), "ADMIN"), 0, 20, "newest", "IN_PROGRESS");
        assertThat(filtered.data()).isEmpty();
        assertThat(service.listForAdmin(auth(admin.getId(), "ADMIN"), 0, 20, "newest", "NEW").meta().totalElements())
                .isEqualTo(2);

        assertThatThrownBy(() -> service.listAssigned(
                auth(historicalAgent.getId(), "AGENT"), -1, 20, "newest", null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.listAssigned(
                auth(historicalAgent.getId(), "AGENT"), 0, 101, "newest", null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.listAssigned(
                auth(historicalAgent.getId(), "AGENT"), 0, 20, "createdAt", null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.listForAdmin(
                auth(admin.getId(), "ADMIN"), 0, 20, "newest", "UNKNOWN"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void adminCollectionIncludesInactiveAccountsAndArchivedProperties() {
        Property property = property(historicalAgent);
        UUID id = inquiry("NEW", buyer, historicalAgent, property);
        jdbc.update("update users set account_status='INACTIVE' where id in (?,?)",
                buyer.getId(), historicalAgent.getId());
        jdbc.update("update properties set status='ARCHIVED', archived_at=now() where id=?",
                property.getId());

        var page = service.listForAdmin(auth(admin.getId(), "ADMIN"), 0, 20, "newest", "NEW");
        assertThat(page.data()).extracting("id").contains(id);
        assertThat(java.util.Arrays.stream(InquiryListItemResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("message");
        assertThat(service.getDetail(auth(admin.getId(), "ADMIN"), id).message())
                .isEqualTo(SENTINEL_MESSAGE);
    }

    @Test
    void auditEventContainsOnlyApprovedMetadata() {
        UUID id = inquiry("NEW", buyer, historicalAgent, property(historicalAgent));
        service.changeStatus(auth(admin.getId(), "ADMIN"), id, patch("IN_PROGRESS"));

        var row = jdbc.queryForMap(
                "select actor_id, entity_id, entity_type, action, before_data::text as before_data, "
                        + "after_data::text as after_data, created_at from audit_logs "
                        + "where entity_id=? and action=?", id, AuditEventService.ACTION_INQUIRY_STATUS_CHANGED);
        assertThat(row.get("actor_id")).isEqualTo(admin.getId());
        assertThat(row.get("entity_id")).isEqualTo(id);
        assertThat(row.get("entity_type")).isEqualTo("INQUIRY");
        assertThat(row.get("action")).isEqualTo("INQUIRY_STATUS_CHANGED");
        assertThat(row.get("before_data").toString()).contains("\"fromStatus\": \"NEW\"");
        assertThat(row.get("after_data").toString())
                .contains("\"toStatus\": \"IN_PROGRESS\"")
                .contains(buyer.getId().toString())
                .contains(historicalAgent.getId().toString());
        assertThat(row.get("after_data").toString())
                .doesNotContain(SENTINEL_SUBJECT)
                .doesNotContain(SENTINEL_MESSAGE);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void closingActiveInquiryReleasesDuplicateEligibility() {
        Property property = property(historicalAgent);
        UUID first = inquiry("NEW", buyer, historicalAgent, property);
        UUID secondId = UUID.randomUUID();
        assertThat(insertInquiry(
                secondId, buyer.getId(), property.getId(), historicalAgent.getId(),
                SENTINEL_SUBJECT, SENTINEL_MESSAGE)).isZero();

        service.changeStatus(auth(admin.getId(), "ADMIN"), first, patch("CLOSED"));
        UUID thirdId = UUID.randomUUID();
        assertThat(insertInquiry(
                thirdId, buyer.getId(), property.getId(), historicalAgent.getId(),
                SENTINEL_SUBJECT, SENTINEL_MESSAGE)).isEqualTo(1);
        createdInquiryIds.add(thirdId);
    }

    private User user(String role, String label) {
        Role entity = roles.findByName(role).orElseThrow();
        User user = new User();
        user.setRole(entity);
        user.setFirstName("Stage65");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode("TestPassw0rd!"));
        user.setAccountStatus(AccountStatus.ACTIVE);
        User saved = users.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Property property(User agent) {
        return property(agent, "lifecycle");
    }

    private Property property(User agent, String label) {
        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                .stream().findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(unique(label));
        property.setSlug(unique(label.replace(' ', '-')));
        property.setDescription("Stage 6.5 lifecycle fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Lifecycle Road");
        property.setLocality("Lifecycle Locality");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setPublishedAt(Instant.now());
        Property saved = properties.saveAndFlush(property);
        createdPropertyIds.add(saved.getId());
        return saved;
    }

    private String tab(String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    private UUID inquiry(String status, User owner, User assignedAgent, Property property) {
        UUID id = UUID.randomUUID();
        assertThat(insertInquiry(
                id, owner.getId(), property.getId(), assignedAgent.getId(),
                SENTINEL_SUBJECT, SENTINEL_MESSAGE)).isEqualTo(1);
        createdInquiryIds.add(id);
        if (!"NEW".equals(status)) {
            Instant resolved = "RESOLVED".equals(status) ? Instant.now().minus(1, ChronoUnit.HOURS) : null;
            jdbc.update("update inquiries set status=?, resolved_at=? where id=?",
                    status, resolved == null ? null : java.sql.Timestamp.from(resolved), id);
        }
        return id;
    }

    private int insertInquiry(
            UUID id, UUID buyerId, UUID propertyId, UUID assignedAgentId,
            String subject, String message) {
        // Fixture writes bypass Spring Data's @Modifying transaction requirement;
        // the production partial unique index remains the duplicate authority.
        return jdbc.update(
                "insert into inquiries "
                        + "(id, buyer_id, property_id, assigned_agent_id, subject, message, "
                        + "status, created_at, updated_at, resolved_at) "
                        + "values (?, ?, ?, ?, ?, ?, 'NEW', now(), now(), null) "
                        + "on conflict (buyer_id, property_id) where status in ('NEW', 'IN_PROGRESS') "
                        + "do nothing",
                id, buyerId, propertyId, assignedAgentId, subject, message);
    }

    private void ageTimestamps(UUID id, boolean resolved) {
        jdbc.update("update inquiries set created_at=?, updated_at=?, resolved_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                resolved ? java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)) : null,
                id);
    }

    private InquiryStatusPatchRequest patch(String status) {
        InquiryStatusPatchRequest request = new InquiryStatusPatchRequest();
        request.setStatus(status);
        return request;
    }

    private static Authentication auth(UUID id, String role) {
        return new UsernamePasswordAuthenticationToken(
                id.toString(), "n/a",
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

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }
}
