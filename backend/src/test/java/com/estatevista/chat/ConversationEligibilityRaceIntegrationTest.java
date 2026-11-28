package com.estatevista.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.estatevista.authorization.SessionAuthenticationToken;
import com.estatevista.chat.dto.CreatedConversation;
import com.estatevista.chat.service.ConversationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deterministic real-PostgreSQL proof that creation eligibility is re-checked
 * after the property and participant rows are locked.
 *
 * <p>A separate connection holds the relevant row lock while creation is
 * started. Once the creation transaction is visibly waiting for that lock, the
 * eligibility-changing update is committed and the lock is released. The old
 * implementation would use its pre-lock read and create an ineligible
 * conversation; the corrected implementation must fail closed with 404.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ConversationEligibilityRaceIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String PASSWORD_HASH = "$2a$10$not-a-real-password-hash";

    @Autowired private DataSource dataSource;
    @Autowired private ConversationService conversations;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID buyerId;
    private UUID agentId;
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        cleanup();
        Role buyerRole = roles.findByName("BUYER").orElseThrow();
        Role agentRole = roles.findByName("AGENT").orElseThrow();

        buyerId = users.saveAndFlush(newUser(buyerRole, "race-buyer")).getId();
        agentId = users.saveAndFlush(newUser(agentRole, "race-agent")).getId();

        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                .stream().findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle(unique("Race Property"));
        property.setSlug(unique("race-property"));
        property.setDescription("Stage 7 creation eligibility race fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(users.findById(agentId).orElseThrow());
        property.setCreatedBy(users.findById(agentId).orElseThrow());
        property.setPublishedAt(Instant.now());
        propertyId = properties.saveAndFlush(property).getId();
    }

    @AfterEach
    void cleanup() {
        if (propertyId != null) {
            jdbcTemplate.update("""
                    DELETE FROM messages WHERE conversation_id IN
                        (SELECT id FROM conversations WHERE property_id = ?)
                    """, propertyId);
            jdbcTemplate.update("""
                    DELETE FROM conversation_read_states WHERE conversation_id IN
                        (SELECT id FROM conversations WHERE property_id = ?)
                    """, propertyId);
            jdbcTemplate.update("DELETE FROM conversations WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        if (buyerId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
        }
        if (agentId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", agentId);
        }
        buyerId = null;
        agentId = null;
        propertyId = null;
    }

    @Test
    void propertyUnpublishedAfterEligibilityReadAndBeforeLockBlocksCreation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            lockRow(lockConnection, "select id from properties where id = ? for update", propertyId);
            Future<CreatedConversation> creation = executor.submit(
                    () -> conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId));
            awaitWaitingQuery("from properties", creation);

            try (PreparedStatement update = lockConnection.prepareStatement(
                    "update properties set status = 'SOLD' where id = ?")) {
                update.setObject(1, propertyId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            lockConnection.commit();

            assertNotFound(creation);
            assertThat(conversationCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentDeactivatedAfterEligibilityReadAndBeforeLockBlocksCreation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            lockRow(lockConnection, "select id from users where id = ? for update", agentId);
            Future<CreatedConversation> creation = executor.submit(
                    () -> conversations.getOrCreate(auth(buyerId, "BUYER"), propertyId));
            awaitWaitingQuery("from users where id in", creation);

            try (PreparedStatement update = lockConnection.prepareStatement(
                    "update users set account_status = 'INACTIVE' where id = ?")) {
                update.setObject(1, agentId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            lockConnection.commit();

            assertNotFound(creation);
            assertThat(conversationCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private void lockRow(Connection connection, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private void awaitWaitingQuery(String fragment, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*)::int from pg_stat_activity
                    where state = 'active' and wait_event_type = 'Lock' and query ilike ?
                    """, Integer.class, "%" + fragment + "%for update%");
            if (waiting != null && waiting > 0) {
                return;
            }
            if (operation.isDone()) {
                throw new AssertionError("creation finished before the row lock was held");
            }
            Thread.sleep(50);
        }
        throw new AssertionError("creation did not wait for the intended row lock");
    }

    private static void assertNotFound(Future<CreatedConversation> creation) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class, () -> creation.get(30, TimeUnit.SECONDS));
        assertThat(failure.getCause()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure.getCause()).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private long conversationCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from conversations where buyer_id = ? and property_id = ?",
                Long.class, buyerId, propertyId);
        return count == null ? 0L : count;
    }

    private static org.springframework.security.core.Authentication auth(UUID userId, String role) {
        return new SessionAuthenticationToken(
                userId.toString(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static User newUser(Role role, String label) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Race");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPasswordHash(PASSWORD_HASH);
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    private static String unique(String label) {
        return (label + "-" + UUID.randomUUID()).toLowerCase(Locale.ROOT);
    }
}
