package com.estatevista.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real-PostgreSQL verification of the V19 chat idempotency migration.
 *
 * <p>Runs only when {@code TEST_DB_URL} points at a PostgreSQL instance where
 * the test user may create and drop databases. A disposable database is created
 * from that URL, migrated to V18, seeded with a legacy message, and then
 * upgraded to V19 so the data-preserving and constraint assertions exercise a
 * real migration path.</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class V19MigrationIntegrationTest {

    private static final UUID BUYER_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RESIDENTIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID APARTMENT_SUBTYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002001");

    private static final Pattern NETWORK_URL =
            Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?;]+)(\\?.*)?$");
    private static final Pattern LOCAL_URL =
            Pattern.compile("^(jdbc:postgresql:)([^?;]+)(\\?.*)?$");

    private String temporaryDatabaseName;

    @Test
    void v19BackfillsLegacyMessagesAndEnforcesIdempotency() {
        DatabaseUrls urls = DatabaseUrls.parse(System.getenv("TEST_DB_URL"));
        temporaryDatabaseName = "estatevista_v19_" + UUID.randomUUID().toString().replace("-", "");

        JdbcTemplate administration = new JdbcTemplate(dataSource(urls.adminUrl()));
        administration.execute("create database \"" + temporaryDatabaseName + "\"");

        DataSource targetDataSource = dataSource(urls.targetUrl(temporaryDatabaseName));
        JdbcTemplate jdbc = new JdbcTemplate(targetDataSource);

        Flyway.configure()
                .dataSource(targetDataSource)
                .locations("classpath:db/migration")
                .target("18")
                .load()
                .migrate();

        List<HistoryRow> at18 = history(jdbc);
        assertThat(at18).hasSize(18);
        assertThat(at18.stream().map(HistoryRow::version).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 18).boxed().toList());

        Timestamp expectedSentAt = Timestamp.from(Instant.parse("2026-01-02T03:04:05Z"));
        LegacyRow legacy = insertLegacyRow(jdbc, expectedSentAt);

        Flyway.configure()
                .dataSource(targetDataSource)
                .locations("classpath:db/migration")
                .target("19")
                .load()
                .migrate();

        List<HistoryRow> at19 = history(jdbc);
        assertThat(at19).hasSize(19);
        assertThat(at19.stream().map(HistoryRow::version).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 19).boxed().toList());
        assertThat(at19.subList(0, 18)).isEqualTo(at18);
        assertThat(at19.stream().map(HistoryRow::checksum).toList().subList(0, 18))
                .isEqualTo(at18.stream().map(HistoryRow::checksum).toList());
        // version is stored as text, so MAX(version) is lexicographic. The last
        // successful installed_rank is the authoritative final applied version.
        assertThat(jdbc.queryForObject("""
                select version from flyway_schema_history
                where success = true
                order by installed_rank desc
                limit 1
                """, Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class))
                .isZero();

        UUID backfilledKey = jdbc.queryForObject(
                "select idempotency_key from messages where id = ?", UUID.class, legacy.messageId());
        assertThat(backfilledKey).isNotNull();
        assertThat(jdbc.queryForObject(
                "select content from messages where id = ?", String.class, legacy.messageId()))
                .isEqualTo(legacy.content());
        assertThat(jdbc.queryForObject(
                "select sender_id from messages where id = ?", UUID.class, legacy.messageId()))
                .isEqualTo(legacy.buyerId());
        assertThat(jdbc.queryForObject(
                "select sent_at from messages where id = ?", Timestamp.class, legacy.messageId()))
                .isEqualTo(expectedSentAt);
        assertThat(columnType(jdbc, "messages", "idempotency_key")).isEqualTo("uuid");
        assertThat(jdbc.queryForObject("""
                select is_nullable
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'messages'
                  and column_name = 'idempotency_key'
                """, String.class)).isEqualTo("NO");

        assertThat(constraintExists(jdbc, "uk_messages_conversation_sender_idempotency", "u")).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                insert into messages
                    (id, conversation_id, sender_id, message_type, content, sent_at, idempotency_key)
                values (?, ?, ?, 'TEXT', ?, ?, ?)
                """, UUID.randomUUID(), legacy.conversationId(), legacy.buyerId(),
                "Duplicate idempotency key message", expectedSentAt, backfilledKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(indexDefinition(jdbc, "messages", "idx_messages_conversation_order"))
                .contains("(conversation_id, sent_at, id)");
        assertThat(indexDefinition(jdbc, "conversation_read_states", "idx_conversation_read_states_user"))
                .contains("(user_id, conversation_id)");

        // Force the planner away from a sequential scan on the tiny fixture so
        // the approved descending order index is demonstrably usable by the
        // bounded history query shape.
        administration.execute("alter database \"" + temporaryDatabaseName
                + "\" set enable_seqscan = off");
        String plan = jdbc.queryForObject("""
                explain (format json)
                select id from messages
                where conversation_id = ? and deleted_at is null
                order by sent_at desc, id desc
                limit 31
                """, String.class, legacy.conversationId());
        assertThat(plan).contains("idx_messages_conversation_order");
    }

    @AfterEach
    void dropTemporaryDatabase() {
        if (temporaryDatabaseName == null) {
            return;
        }

        JdbcTemplate administration = new JdbcTemplate(
                dataSource(DatabaseUrls.parse(System.getenv("TEST_DB_URL")).adminUrl()));
        administration.queryForList("""
                select pg_terminate_backend(pid)
                from pg_stat_activity
                where datname = ? and pid <> pg_backend_pid()
                """, Object.class, temporaryDatabaseName);
        administration.execute("drop database if exists \"" + temporaryDatabaseName + "\"");
    }

    private static LegacyRow insertLegacyRow(JdbcTemplate jdbc, Timestamp sentAt) {
        UUID buyerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        String content = "Legacy message before V19";

        insertUser(jdbc, buyerId, BUYER_ROLE_ID, "v19-buyer-" + suffix, now);
        insertUser(jdbc, agentId, AGENT_ROLE_ID, "v19-agent-" + suffix, now);
        insertUser(jdbc, adminId, ADMIN_ROLE_ID, "v19-admin-" + suffix, now);

        jdbc.update("""
                insert into properties
                    (id, title, description, category_id, subtype_id, listing_type, price,
                     currency_code, city, state, country, status, primary_agent_id, created_by,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, 'SALE', ?, 'USD', 'Bengaluru', 'Karnataka', 'India',
                        'PUBLISHED', ?, ?, ?, ?)
                """, propertyId, "V19 legacy property " + suffix,
                "Property used to verify the V19 chat migration.", RESIDENTIAL_CATEGORY_ID,
                APARTMENT_SUBTYPE_ID, new BigDecimal("250000.00"), agentId, adminId, now, now);

        jdbc.update("""
                insert into conversations
                    (id, buyer_id, property_id, assigned_agent_id, status, created_at, updated_at)
                values (?, ?, ?, ?, 'OPEN', ?, ?)
                """, conversationId, buyerId, propertyId, agentId, now, now);

        jdbc.update("""
                insert into messages
                    (id, conversation_id, sender_id, message_type, content, sent_at)
                values (?, ?, ?, 'TEXT', ?, ?)
                """, messageId, conversationId, buyerId, content, sentAt);

        return new LegacyRow(buyerId, conversationId, messageId, content);
    }

    private static void insertUser(
            JdbcTemplate jdbc, UUID id, UUID roleId, String emailLocalPart, Timestamp now) {
        jdbc.update("""
                insert into users
                    (id, role_id, first_name, last_name, email, password_hash, account_status,
                     created_at, updated_at)
                values (?, ?, 'V19', 'Migration', ?, 'not-a-real-password-hash', 'ACTIVE', ?, ?)
                """, id, roleId, emailLocalPart + "@example.com", now, now);
    }

    private static List<HistoryRow> history(JdbcTemplate jdbc) {
        return jdbc.query("""
                select version, checksum
                from flyway_schema_history
                where success = true
                order by installed_rank
                """, (rs, rowNum) -> new HistoryRow(
                        rs.getInt("version"), (Integer) rs.getObject("checksum")));
    }

    private static boolean constraintExists(JdbcTemplate jdbc, String name, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1
                    from pg_constraint constraint_row
                    join pg_class relation on relation.oid = constraint_row.conrelid
                    join pg_namespace namespace on namespace.oid = relation.relnamespace
                    where namespace.nspname = 'public'
                      and relation.relname = 'messages'
                      and constraint_row.conname = ?
                      and constraint_row.contype = ?
                )
                """, Boolean.class, name, type));
    }

    private static String indexDefinition(JdbcTemplate jdbc, String tableName, String indexName) {
        return jdbc.queryForObject("""
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and tablename = ?
                  and indexname = ?
                """, String.class, tableName, indexName);
    }

    private static String columnType(JdbcTemplate jdbc, String tableName, String columnName) {
        return jdbc.queryForObject("""
                select format_type(attribute.atttypid, attribute.atttypmod)
                from pg_attribute attribute
                join pg_class relation on relation.oid = attribute.attrelid
                join pg_namespace namespace on namespace.oid = relation.relnamespace
                where namespace.nspname = 'public'
                  and relation.relname = ?
                  and attribute.attname = ?
                  and attribute.attnum > 0
                  and not attribute.attisdropped
                """, String.class, tableName, columnName);
    }

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);

        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        if (username != null && !username.isBlank()) {
            dataSource.setUsername(username);
        }
        if (password != null) {
            dataSource.setPassword(password);
        }
        return dataSource;
    }

    private record HistoryRow(int version, Integer checksum) {
    }

    private record LegacyRow(UUID buyerId, UUID conversationId, UUID messageId, String content) {
    }

    private record DatabaseUrls(String adminUrl, String targetPrefix, String query) {

        static DatabaseUrls parse(String url) {
            Matcher matcher = NETWORK_URL.matcher(url);
            if (!matcher.matches()) {
                matcher = LOCAL_URL.matcher(url);
            }
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Unsupported TEST_DB_URL: " + url);
            }

            String query = matcher.group(3) == null ? "" : matcher.group(3);
            return new DatabaseUrls(matcher.group(1) + "postgres" + query, matcher.group(1), query);
        }

        String targetUrl(String databaseName) {
            return targetPrefix + databaseName + query;
        }
    }
}
