package com.estatevista.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.estatevista.audit.AuditEventService;
import com.estatevista.websocket.AuthoritativeSessionValidator;
import com.estatevista.websocket.WebSocketConnectionRegistry;
import com.estatevista.websocket.WebSocketSessionRevocationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.MessageHandlingRunnable;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-transport Stage 7.4 verification of {@code /app/chat.send}.
 *
 * <p>Every case uses a live Spring Boot server, the real security filter chain,
 * real Spring Session JDBC persistence, real repositories, and native
 * WebSocket/STOMP frames. Handshake, broker, registry and dispatch components
 * are never mocked. The suite is gated on {@code TEST_DB_URL} so a
 * database-free build cannot claim coverage.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.ws-connection-attempt.max-attempts=50",
                "app.rate-limit.ws-message-user.max-attempts=50",
                "app.rate-limit.ws-message-conversation.max-attempts=50",
                "app.websocket-security.max-sockets-per-user=5",
                "app.websocket-security.max-subscriptions-per-connection=3",
                "app.websocket-security.max-frame-bytes=32768",
                "app.websocket-security.revalidation-interval-ms=60000",
                "app.websocket-security.validation-cache-ttl-ms=0"
        })
@ActiveProfiles("test")
@Import(ChatWebSocketIntegrationTest.ControlledOutboundChannelConfig.class)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ChatWebSocketIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Chat-Password-123";
    private static final String WS_ENDPOINT = "/ws";
    private static final String SEND_DESTINATION = "/app/chat.send";
    private static final UUID ADMIN_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EVENT_WINDOW = Duration.ofSeconds(2);
    private static final Duration QUIET_WINDOW = Duration.ofMillis(800);
    private static final Pattern STOMP_BODY_SEPARATOR = Pattern.compile("\\r?\\n\\r?\\n");
    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SUBTYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002002");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebSocketConnectionRegistry registry;

    @Autowired
    private WebSocketSessionRevocationService revocation;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private AuthoritativeSessionValidator validator;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private final List<Fixture> createdFixtures = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        ControlledOutboundExecutor.INSTANCE.reset();
        for (WebSocketConnectionRegistry.ConnectionRecord record : List.copyOf(registry.all())) {
            revocation.revokeConnection(
                    record, WebSocketSessionRevocationService.REASON_SESSION_REVOKED);
        }
        cleanUpDatabaseFixtures();
    }

    /**
     * These fixtures use real HTTP and committed transactions, so JUnit cannot
     * roll them back. Remove them explicitly; otherwise their published listings
     * leak into the fixture-scoped public-discovery tests later in the same
     * database-backed suite.
     */
    private void cleanUpDatabaseFixtures() {
        Set<UUID> propertyIds = new LinkedHashSet<>();
        Set<UUID> conversationIds = new LinkedHashSet<>();
        for (Fixture fixture : createdFixtures) {
            propertyIds.add(fixture.propertyId());
            conversationIds.add(fixture.conversationId());
        }

        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID conversationId : conversationIds) {
            jdbcTemplate.update(
                    "DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM conversation_read_states WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID propertyId : propertyIds) {
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update(
                    "DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
        createdFixtures.clear();
        createdUserIds.clear();
        createdSessionIds.clear();
    }

    @Test
    void validSendPersistsServerDerivedFieldsAndDeliversSenderOnlyAck() throws Exception {
        Fixture fixture = newConversationFixture("valid");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            UUID idempotencyKey = UUID.randomUUID();
            String content = "A real STOMP message from the buyer";
            send(buyer.session, fixture.conversationId(), content, idempotencyKey);

            List<ChatFrame> buyerFrames = awaitChatFrames(buyer, EVENT_WINDOW);
            List<ChatFrame> agentFrames = awaitChatFrames(agent, EVENT_WINDOW);
            List<MessageRow> rows = messageRows(fixture.conversationId());

            assertThat(rows).hasSize(1);
            MessageRow row = rows.get(0);
            assertThat(row.senderId()).isEqualTo(fixture.buyer().userId());
            assertThat(row.sentAt()).isNotNull();
            assertThat(row.idempotencyKey()).isEqualTo(idempotencyKey);
            assertThat(row.content()).isEqualTo(content);
            assertThat(row.messageType()).isEqualTo("TEXT");

            assertThat(buyerFrames).hasSize(2);
            JsonNode created = singleEvent(buyerFrames, "MESSAGE_CREATED");
            JsonNode ack = singleEvent(buyerFrames, "PERSISTENCE_ACK");
            assertThat(created.path("id").asText()).isEqualTo(row.id().toString());
            assertThat(created.path("conversationId").asText())
                    .isEqualTo(fixture.conversationId().toString());
            assertThat(created.path("senderId").asText())
                    .isEqualTo(fixture.buyer().userId().toString());
            assertThat(created.path("content").asText()).isEqualTo(content);
            assertThat(created.path("idempotencyKey").asText()).isEqualTo(idempotencyKey.toString());
            assertThat(created.path("sentAt").asText()).isNotBlank();

            // The acknowledgement correlates to the same durable row and never
            // carries private content. It is not a native STOMP RECEIPT.
            assertThat(ack.path("id").asText()).isEqualTo(row.id().toString());
            assertThat(ack.path("conversationId").asText())
                    .isEqualTo(fixture.conversationId().toString());
            assertThat(isAbsentOrNull(ack.path("senderId"))).isTrue();
            assertThat(ack.path("idempotencyKey").asText()).isEqualTo(idempotencyKey.toString());
            assertThat(isAbsentOrNull(ack.path("content"))).isTrue();

            assertThat(agentFrames).hasSize(1);
            assertThat(events(agentFrames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(events(agentFrames, "PERSISTENCE_ACK")).isEmpty();
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void spoofedIdentityHeadersAndPayloadFieldsCannotChangeTheAuthenticatedSender() throws Exception {
        Fixture fixture = newConversationFixture("spoof");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            UUID key = UUID.randomUUID();
            String content = "Sender comes from the trusted session";
            String spoofHeaders = "sender-id:" + fixture.agent().userId() + "\n"
                    + "role:ADMIN\n"
                    + "login:admin@example.com\n"
                    + "X-User-Id:" + fixture.agent().userId() + "\n";
            send(buyer.session, fixture.conversationId(), content, key, spoofHeaders);

            assertThat(awaitChatFrames(buyer, EVENT_WINDOW)).hasSize(2);
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).hasSize(1);
            List<MessageRow> rows = messageRows(fixture.conversationId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).senderId()).isEqualTo(fixture.buyer().userId());
            assertThat(rows.get(0).idempotencyKey()).isEqualTo(key);

            // Unknown JSON identity fields are rejected safely before persistence.
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("conversationId", fixture.conversationId().toString());
            fields.put("content", "spoofed payload");
            fields.put("idempotencyKey", UUID.randomUUID().toString());
            fields.put("senderId", fixture.agent().userId().toString());
            fields.put("role", "ADMIN");
            fields.put("login", "admin@example.com");
            sendRaw(buyer.session, objectMapper.writeValueAsString(fields), "");

            List<ChatFrame> errors = awaitChatFrames(buyer, EVENT_WINDOW);
            assertThat(singleEvent(errors, "MESSAGE_ERROR").path("reason").asText())
                    .isEqualTo("UNKNOWN_FIELD");
            assertThat(events(errors, "MESSAGE_CREATED")).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void exactRetryAddsOneSenderAckOnlyAndLeavesOneMessageAndAuditRow() throws Exception {
        Fixture fixture = newConversationFixture("retry");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            UUID key = UUID.randomUUID();
            String content = "Retry me exactly";
            send(buyer.session, fixture.conversationId(), content, key);

            List<ChatFrame> firstBuyerFrames = awaitChatFrames(buyer, EVENT_WINDOW);
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).hasSize(1);
            List<MessageRow> firstRows = messageRows(fixture.conversationId());
            assertThat(firstRows).hasSize(1);
            MessageRow row = firstRows.get(0);
            int auditRowsBefore = messageAuditRows(row.id());

            send(buyer.session, fixture.conversationId(), content, key);

            List<ChatFrame> retryBuyerFrames = awaitChatFrames(buyer, EVENT_WINDOW);
            assertThat(retryBuyerFrames).hasSize(1);
            JsonNode retryAck = singleEvent(retryBuyerFrames, "PERSISTENCE_ACK");
            assertThat(retryAck.path("id").asText()).isEqualTo(row.id().toString());
            assertThat(retryAck.path("idempotencyKey").asText()).isEqualTo(key.toString());
            assertThat(events(retryBuyerFrames, "MESSAGE_CREATED")).isEmpty();
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).isEmpty();

            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(messageAuditRows(row.id())).isEqualTo(auditRowsBefore);
            // Every fixture send carries an explicit key; sentAt remains a
            // server-owned value on both the original and retried attempts.
            assertThat(row.sentAt()).isNotNull();
            assertThat(firstBuyerFrames).hasSize(2);
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void reusedKeyWithDifferentContentProducesSafeIdempotencyConflict() throws Exception {
        Fixture fixture = newConversationFixture("conflict");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            UUID key = UUID.randomUUID();
            send(buyer.session, fixture.conversationId(), "Original content", key);
            assertThat(awaitChatFrames(buyer, EVENT_WINDOW)).hasSize(2);
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).hasSize(1);
            MessageRow original = messageRows(fixture.conversationId()).get(0);
            int auditRowsBefore = messageAuditRows(original.id());

            send(buyer.session, fixture.conversationId(), "Different content", key);

            List<ChatFrame> errors = awaitChatFrames(buyer, EVENT_WINDOW);
            JsonNode error = singleEvent(errors, "MESSAGE_ERROR");
            assertThat(error.path("reason").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(error.path("conversationId").asText())
                    .isEqualTo(fixture.conversationId().toString());
            assertThat(error.path("idempotencyKey").asText()).isEqualTo(key.toString());
            assertThat(error.path("content").isMissingNode()
                    || error.path("content").isNull()).isTrue();
            assertThat(events(errors, "MESSAGE_CREATED")).isEmpty();
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).isEmpty();

            List<MessageRow> rows = messageRows(fixture.conversationId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).content()).isEqualTo("Original content");
            assertThat(rows.get(0).idempotencyKey()).isEqualTo(key);
            assertThat(rows.get(0).sentAt()).isNotNull();
            assertThat(messageAuditRows(original.id())).isEqualTo(auditRowsBefore);
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void malformedPayloadsAreRejectedSafelyWithoutPersistenceOrPeerDelivery() throws Exception {
        Fixture fixture = newConversationFixture("invalid");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            int rejectionAuditsBefore = rejectionAuditRows(fixture.conversationId());
            String conversationId = fixture.conversationId().toString();
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("conversationId", conversationId);
            blank.put("content", "   ");
            blank.put("idempotencyKey", UUID.randomUUID().toString());

            Map<String, Object> tooLong = new LinkedHashMap<>();
            tooLong.put("conversationId", conversationId);
            tooLong.put("content", "x".repeat(2001));
            tooLong.put("idempotencyKey", UUID.randomUUID().toString());

            Map<String, Object> badKey = new LinkedHashMap<>();
            badKey.put("conversationId", conversationId);
            badKey.put("content", "valid body");
            badKey.put("idempotencyKey", "not-a-uuid");

            Map<String, Object> missingKey = new LinkedHashMap<>();
            missingKey.put("conversationId", conversationId);
            missingKey.put("content", "missing key");

            Map<String, Object> unknownField = new LinkedHashMap<>();
            unknownField.put("conversationId", conversationId);
            unknownField.put("content", "unknown field");
            unknownField.put("idempotencyKey", UUID.randomUUID().toString());
            unknownField.put("nothingToSeeHere", "spoof");

            for (Map<String, Object> payload : List.of(
                    blank, tooLong, badKey, missingKey, unknownField)) {
                sendRaw(buyer.session, objectMapper.writeValueAsString(payload), "");
            }

            List<ChatFrame> errors = awaitChatFrames(buyer, Duration.ofSeconds(3));
            assertThat(events(errors, "MESSAGE_ERROR"))
                    .extracting(error -> error.path("reason").asText())
                    .containsExactlyInAnyOrder(
                            "CONTENT_REQUIRED", "CONTENT_TOO_LONG", "INVALID_IDENTIFIER",
                            "INVALID_IDENTIFIER", "UNKNOWN_FIELD");
            assertThat(events(errors, "MESSAGE_CREATED")).isEmpty();
            assertThat(events(errors, "PERSISTENCE_ACK")).isEmpty();
            assertThat(errors).hasSize(5);
            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).isEmpty();

            List<String> auditPayloads = rejectionAuditPayloads(fixture.conversationId());
            assertThat(auditPayloads).hasSize(rejectionAuditsBefore + 5);
            assertThat(auditPayloads).anySatisfy(payload -> {
                JsonNode audit = objectMapper.readTree(payload);
                assertThat(audit.path("outcome").asText()).isEqualTo("REJECTED");
                assertThat(audit.path("reason").asText()).isEqualTo("UNKNOWN_FIELD");
                assertThat(audit.path("correlationId").asText()).isNotBlank();
            });
            assertThat(rejectionAuditActors(fixture.conversationId()))
                    .contains(fixture.buyer().userId().toString());
            assertThat(auditPayloads).noneMatch(payload -> payload.contains("unknown field")
                    || payload.contains("missing key")
                    || payload.contains("valid body")
                    || payload.contains("spoof"));
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void nonParticipantBuyerAndAdminCannotSendIntoTheConversation() throws Exception {
        Fixture fixture = newConversationFixture("denied");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        Credentials outsider = newUser("denied-outsider");
        Connected outsiderSocket = connectAndAuthenticate(outsider);
        Credentials admin = newUserWithRole("denied-admin", "ADMIN");
        try {
            try {
                send(outsiderSocket.session, fixture.conversationId(), "intrusion", UUID.randomUUID());
            } catch (IOException | IllegalStateException closedWhileWriting) {
                // The boundary may close the transport before the write completes.
            }
            List<String> outsiderFrames = awaitRawFrames(outsiderSocket, EVENT_WINDOW);
            assertThat(outsiderFrames.stream().anyMatch(frame -> frame.startsWith("ERROR"))
                    || outsiderSocket.handler.closed.isDone()).isTrue();

            // ADMIN fails at the real HTTP handshake, so no sending socket exists.
            assertThatThrownBy(() -> openSocket(handshakeHeaders(admin.cookie(), ORIGIN)))
                    .hasMessageContaining("403");

            assertThat(messageRows(fixture.conversationId())).isEmpty();
            assertThat(awaitChatFrames(buyer, QUIET_WINDOW)).isEmpty();
            assertThat(awaitChatFrames(agent, QUIET_WINDOW)).isEmpty();
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
            closeQuietly(outsiderSocket);
        }
    }

    @Test
    void framesAreNotDeliveredToParticipantsOfAnotherConversation() throws Exception {
        Fixture first = newConversationFixture("isolation-first");
        Fixture second = newConversationFixture("isolation-second");
        Connected firstBuyer = connectAndAuthenticate(first.buyer());
        Connected firstAgent = connectAndAuthenticate(first.agent());
        Connected secondBuyer = connectAndAuthenticate(second.buyer());
        Connected secondAgent = connectAndAuthenticate(second.agent());
        try {
            UUID key = UUID.randomUUID();
            String content = "Only for the first conversation";
            send(firstBuyer.session, first.conversationId(), content, key);

            // The sender-only ack is emitted after commit, so it is the
            // deterministic synchronization point for database assertions.
            List<ChatFrame> senderFrames = awaitChatFrames(firstBuyer, EVENT_WINDOW);
            assertThat(events(senderFrames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(events(senderFrames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(messageRows(first.conversationId())).hasSize(1);
            assertThat(messageRows(second.conversationId())).isEmpty();
            assertThat(events(awaitChatFrames(firstAgent, EVENT_WINDOW), "MESSAGE_CREATED"))
                    .hasSize(1);

            assertThat(awaitChatFrames(secondBuyer, EVENT_WINDOW)).isEmpty();
            assertThat(awaitChatFrames(secondAgent, EVENT_WINDOW)).isEmpty();
        } finally {
            closeQuietly(firstBuyer);
            closeQuietly(firstAgent);
            closeQuietly(secondBuyer);
            closeQuietly(secondAgent);
        }
    }

    @Test
    void revokedSocketReceivesNoChatEventsAndCannotSendAfterLogout() throws Exception {
        Fixture fixture = newConversationFixture("revoked");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        try {
            assertThat(logout(fixture.buyer()).statusCode()).isEqualTo(200);
            assertThat(buyer.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);

            try {
                send(buyer.session, fixture.conversationId(), "after logout", UUID.randomUUID());
            } catch (IOException | IllegalStateException closedWhileWriting) {
                // A revoked transport is closed during or before the write.
            }
            assertThat(messageRows(fixture.conversationId())).isEmpty();
            assertThat(awaitChatFrames(buyer, QUIET_WINDOW)).isEmpty();

            UUID key = UUID.randomUUID();
            send(agent.session, fixture.conversationId(), "sent after buyer logout", key);

            List<ChatFrame> agentFrames = awaitChatFrames(agent, EVENT_WINDOW);
            assertThat(events(agentFrames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(events(agentFrames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(awaitChatFrames(buyer, EVENT_WINDOW)).isEmpty();
            List<MessageRow> rows = messageRows(fixture.conversationId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).senderId()).isEqualTo(fixture.agent().userId());
            assertThat(rows.get(0).idempotencyKey()).isEqualTo(key);
            assertThat(rows.get(0).sentAt()).isNotNull();

            // This socket is revoked before the agent publishes, so it proves
            // suppression for a revoked session and rejection of sends from it.
            // The queued-after-publish race is covered deterministically by the
            // controlled outbound executor tests below.
        } finally {
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void queuedMessageCreatedIsDroppedAtTransportWhenRecipientSessionIsRevoked() throws Exception {
        Fixture fixture = newConversationFixture("transport-created");
        Connected buyer = connectAndAuthenticate(fixture.buyer());
        Connected agent = connectAndAuthenticate(fixture.agent());
        String agentSession = soleStompSessionId(fixture.agent());
        Gate gate = arm(1, agentSession);
        CompletableFuture<Void> send = sendAsync(
                buyer.session, fixture.conversationId(), "queued before logout", UUID.randomUUID());
        try {
            awaitQueued(gate);
            // The recipient frame is held in the server-controlled outbound
            // queue, so no earlier frame can be mistaken for a queued frame.
            assertThat(awaitChatFrames(agent, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            assertThat(logout(fixture.agent()).statusCode()).isEqualTo(200);
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(agent, EVENT_WINDOW)).isEmpty();
            List<ChatFrame> senderFrames = awaitChatFrames(buyer, EVENT_WINDOW);
            assertThat(events(senderFrames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(events(senderFrames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            // Logout closes the real transport before the app wrapper runs, so
            // the authoritative evidence is the session revocation itself.
            assertThat(countRevocationAudits(
                    fixture.agent().userId(), WebSocketSessionRevocationService.REASON_LOGOUT))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(buyer);
            closeQuietly(agent);
        }
    }

    @Test
    void queuedAckIsDroppedOnlyForRevokedSenderConnectionAndExactRetryRemainsSenderScoped()
            throws Exception {
        Fixture fixture = newConversationFixture("transport-ack");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        Set<String> beforeSecond = stompSessionIds(fixture.buyer());
        Connected otherSenderSession = connectAndAuthenticate(fixture.buyer());
        String secondSession = stompSessionIds(fixture.buyer()).stream()
                .filter(session -> !beforeSecond.contains(session))
                .findFirst().orElseThrow();
        Gate gate = arm(2, secondSession);
        UUID key = UUID.randomUUID();
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "ack targeting", key);
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(otherSenderSession, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            revokeConnectionByStomp(secondSession, WebSocketSessionRevocationService.REASON_SESSION_REVOKED);
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(otherSenderSession, EVENT_WINDOW)).isEmpty();
            List<ChatFrame> senderFrames = awaitChatFrames(sender, EVENT_WINDOW);
            assertThat(events(senderFrames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(events(senderFrames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_SESSION_REVOKED))
                    .isGreaterThanOrEqualTo(1);

            // convertAndSendToUser targets every live session of the sender, not
            // only the originating connection. An exact retry emits one ack to
            // the remaining live session and never republishes MESSAGE_CREATED.
            send(sender.session, fixture.conversationId(), "ack targeting", key);
            List<ChatFrame> retryFrames = awaitChatFrames(sender, EVENT_WINDOW);
            assertThat(events(retryFrames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(events(retryFrames, "MESSAGE_CREATED")).isEmpty();
            assertThat(awaitChatFrames(otherSenderSession, QUIET_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
        } finally {
            release(gate);
            closeQuietly(sender);
            closeQuietly(otherSenderSession);
        }
    }

    @Test
    void accountWideRevocationDropsQueuedFramesForEveryConnectionOfTheAccount() throws Exception {
        Fixture fixture = newConversationFixture("transport-account");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String firstSession = soleStompSessionId(fixture.buyer());
        Connected otherSenderSession = connectAndAuthenticate(fixture.buyer());
        String secondSession = stompSessionIds(fixture.buyer()).stream()
                .filter(session -> !firstSession.equals(session))
                .findFirst().orElseThrow();
        Gate gate = arm(4, Set.of(firstSession, secondSession));
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "account revocation", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            assertThat(awaitChatFrames(otherSenderSession, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            assertThat(revocation.revokeAccount(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_ACCOUNT_REVOKED))
                    .isGreaterThanOrEqualTo(2);
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(awaitChatFrames(otherSenderSession, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_ACCOUNT_REVOKED))
                    .isGreaterThanOrEqualTo(2);
        } finally {
            release(gate);
            closeQuietly(sender);
            closeQuietly(otherSenderSession);
        }
    }

    @Test
    void queuedFrameIsDroppedWhenAuthoritativeSessionValidationFailsAfterQueueing() throws Exception {
        Fixture fixture = newConversationFixture("transport-validation");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(2, stompSessionId);
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "validation failure", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            WebSocketConnectionRegistry.ConnectionRecord record =
                    registry.byStompSession(stompSessionId);
            assertThat(record).isNotNull();
            validator.deleteSession(record.springSessionId());
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    @Test
    void queuedFrameIsDroppedWhenAccountStatusIsLostAfterQueueing() throws Exception {
        Fixture fixture = newConversationFixture("transport-status");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(2, stompSessionId);
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "status loss", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            jdbcTemplate.update(
                    "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                    fixture.buyer().userId());
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    @Test
    void queuedFrameIsDroppedWhenChatRoleIsLostAfterQueueing() throws Exception {
        Fixture fixture = newConversationFixture("transport-role");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(2, stompSessionId);
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "role loss", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            jdbcTemplate.update(
                    "UPDATE users SET role_id = ? WHERE id = ?", ADMIN_ROLE_ID, fixture.buyer().userId());
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    @Test
    void queuedErrorFrameOnTheErrorsQueueIsDroppedAfterRevocation() throws Exception {
        Fixture fixture = newConversationFixture("transport-error");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(1, stompSessionId);
        CompletableFuture<Void> send = CompletableFuture.runAsync(() -> {
            try {
                sendRaw(sender.session, "{\"conversationId\":\"" + fixture.conversationId()
                        + "\",\"content\":\"missing key\"}", "");
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            revokeConnectionByStomp(stompSessionId, WebSocketSessionRevocationService.REASON_SESSION_REVOKED);
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).isEmpty();
            assertThat(countRevocationAudits(
                    fixture.buyer().userId(), WebSocketSessionRevocationService.REASON_SESSION_REVOKED))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    @Test
    void queuedFrameIsDroppedByFinalTransportCheckWhenSubscriptionAuthorityIsRemovedAfterQueueing()
            throws Exception {
        Fixture fixture = newConversationFixture("transport-subscription");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(2, stompSessionId);
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "subscription loss", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            WebSocketConnectionRegistry.ConnectionRecord record =
                    registry.byStompSession(stompSessionId);
            assertThat(record).isNotNull();
            registry.removeSubscription(record, "messages");
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            // Both frames passed the channel check before queueing. The real
            // transport still exists, so their suppression and TRANSPORT_ audit
            // prove the final authorization recheck immediately before the write.
            assertThat(awaitChatFrames(sender, EVENT_WINDOW)).isEmpty();
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countWsAuthFailures(
                    fixture.buyer().userId(), "TRANSPORT_SUBSCRIPTION_MISSING"))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    @Test
    void queuedPrivateFrameIsDeliveredWhenNoRevocationOccurs() throws Exception {
        Fixture fixture = newConversationFixture("transport-allowed");
        Connected sender = connectAndAuthenticate(fixture.buyer());
        String stompSessionId = soleStompSessionId(fixture.buyer());
        Gate gate = arm(2, stompSessionId);
        CompletableFuture<Void> send = sendAsync(
                sender.session, fixture.conversationId(), "normal delivery", UUID.randomUUID());
        try {
            awaitQueued(gate);
            assertThat(awaitChatFrames(sender, QUIET_WINDOW)).isEmpty();
            ControlledOutboundExecutor.INSTANCE.disarm();
            release(gate);
            send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            List<ChatFrame> frames = awaitChatFrames(sender, EVENT_WINDOW);
            assertThat(events(frames, "PERSISTENCE_ACK")).hasSize(1);
            assertThat(events(frames, "MESSAGE_CREATED")).hasSize(1);
            assertThat(messageRows(fixture.conversationId())).hasSize(1);
            assertThat(countWsAuthFailures(
                    fixture.buyer().userId(), "TRANSPORT_SESSION_CLOSED")).isZero();
        } finally {
            release(gate);
            closeQuietly(sender);
        }
    }

    private Fixture newConversationFixture(String label) throws Exception {
        Credentials buyer = newUser(label + "-buyer");
        Credentials agent = newUserWithRole(label + "-agent", "AGENT");
        UUID propertyId = publishProperty(agent.userId(), label);
        UUID conversationId = createConversation(buyer, propertyId);
        Fixture fixture = new Fixture(buyer, agent, propertyId, conversationId);
        createdFixtures.add(fixture);
        return fixture;
    }

    /** Publishes a unique listing with the trusted agent as its primary agent. */
    private UUID publishProperty(UUID agentId, String label) {
        UUID propertyId = UUID.randomUUID();
        String slug = "chat-74-" + label + "-" + UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO properties (
                            id, title, slug, description, category_id, subtype_id,
                            listing_type, price, currency_code, city, state, country,
                            status, primary_agent_id, created_by, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                propertyId,
                "Chat 7.4 " + label,
                slug,
                "Stage 7.4 real-transport chat fixture",
                CATEGORY_ID,
                SUBTYPE_ID,
                "SALE",
                new BigDecimal("2500000.00"),
                "INR",
                "Vadodara",
                "Gujarat",
                "India",
                "PUBLISHED",
                agentId,
                agentId,
                Timestamp.from(Instant.now()));
        return propertyId;
    }

    private UUID createConversation(Credentials buyer, UUID propertyId) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(apiUri(
                                "/api/v1/properties/" + propertyId + "/conversations"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, buyer.cookie())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", buyer.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 201);
        JsonNode body = objectMapper.readTree(response.body());
        return UUID.fromString(body.path("data").path("conversationId").asText());
    }

    private List<MessageRow> messageRows(UUID conversationId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, sender_id, content, idempotency_key, sent_at, "
                        + "message_type FROM messages WHERE conversation_id = ? "
                        + "ORDER BY sent_at, id",
                (resultSet, rowNumber) -> new MessageRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("conversation_id", UUID.class),
                        resultSet.getObject("sender_id", UUID.class),
                        resultSet.getString("content"),
                        resultSet.getObject("idempotency_key", UUID.class),
                        resultSet.getObject("sent_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("message_type")),
                conversationId);
    }

    private CompletableFuture<Void> sendAsync(
            WebSocketSession session, UUID conversationId, String content, UUID idempotencyKey) {
        return CompletableFuture.runAsync(() -> {
            try {
                send(session, conversationId, content, idempotencyKey);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private Set<String> stompSessionIds(Credentials user) {
        return registry.forUser(user.userId()).stream()
                .map(WebSocketConnectionRegistry.ConnectionRecord::stompSessionId)
                .filter(session -> session != null)
                .collect(java.util.stream.Collectors.toSet());
    }

    private String soleStompSessionId(Credentials user) {
        Set<String> sessions = stompSessionIds(user);
        assertThat(sessions).as("STOMP sessions for %s", user.email()).hasSize(1);
        return sessions.iterator().next();
    }

    private void revokeConnectionByStomp(String stompSessionId, String reason) {
        WebSocketConnectionRegistry.ConnectionRecord record = registry.byStompSession(stompSessionId);
        assertThat(record).as("live STOMP session %s", stompSessionId).isNotNull();
        assertThat(revocation.revokeConnection(record, reason)).isTrue();
    }

    private int countWsAuthFailures(UUID userId, String reason) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_id = ? AND action = ? AND after_data::text LIKE ?
                """, Integer.class, userId, AuditEventService.ACTION_WS_AUTH_FAILURE,
                "%" + reason + "%");
    }

    private int countRevocationAudits(UUID userId, String reason) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_id = ? AND action = ? AND after_data::text LIKE ?
                """, Integer.class, userId, AuditEventService.ACTION_WS_SESSION_REVOKED,
                "%" + reason + "%");
    }

    private Gate arm(int expectedFrames, String stompSessionId, String... types) {
        return arm(expectedFrames, Set.of(stompSessionId), types);
    }

    private Gate arm(int expectedFrames, Set<String> stompSessionIds, String... types) {
        Set<String> acceptedTypes = types.length == 0 ? null : Set.of(types);
        Gate gate = new Gate(expectedFrames, Set.copyOf(stompSessionIds), acceptedTypes);
        ControlledOutboundExecutor.INSTANCE.arm(gate);
        return gate;
    }

    private void awaitQueued(Gate gate) throws InterruptedException {
        assertThat(gate.queued.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("outbound frame reached the controlled executor")
                .isTrue();
    }

    private static void release(Gate gate) {
        gate.release.countDown();
    }

    private int messageAuditRows(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Integer.class,
                AuditEventService.ACTION_MESSAGE_CREATE,
                messageId);
    }

    private int rejectionAuditRows(UUID conversationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE action = ? AND after_data->>'conversationId' = ?
                """, Integer.class, AuditEventService.ACTION_MESSAGE_REJECTED,
                conversationId.toString());
    }

    private List<String> rejectionAuditPayloads(UUID conversationId) {
        return jdbcTemplate.queryForList("""
                SELECT after_data::text FROM audit_logs
                WHERE action = ? AND after_data->>'conversationId' = ?
                ORDER BY created_at
                """, String.class, AuditEventService.ACTION_MESSAGE_REJECTED,
                conversationId.toString());
    }

    private List<String> rejectionAuditActors(UUID conversationId) {
        return jdbcTemplate.queryForList("""
                SELECT actor_id::text FROM audit_logs
                WHERE action = ? AND after_data->>'conversationId' = ?
                """, String.class, AuditEventService.ACTION_MESSAGE_REJECTED,
                conversationId.toString());
    }

    private HttpResponse<String> logout(Credentials user) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/logout"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, user.cookie())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-TOKEN", user.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Connected connectAndAuthenticate(Credentials user) throws Exception {
        Connected connected = openSocket(handshakeHeaders(user.cookie(), ORIGIN));
        StringBuilder frame = new StringBuilder("CONNECT\naccept-version:1.2\nheart-beat:0,0\n")
                .append("X-CSRF-TOKEN:").append(user.csrfToken()).append("\n\n\u0000");
        send(connected.session, frame.toString());
        awaitConnected(connected);

        send(connected.session,
                "SUBSCRIBE\nid:messages\ndestination:/user/queue/messages\n\n\u0000");
        send(connected.session,
                "SUBSCRIBE\nid:errors\ndestination:/user/queue/errors\n\n\u0000");
        awaitSubscriptions(user);
        return connected;
    }

    /**
     * Waits for both the application registry and Spring's broker registry to
     * observe the two subscriptions before any SEND. The broker registry check
     * removes a dispatch race when several sockets connect in quick succession.
     */
    private void awaitSubscriptions(Credentials user) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean appReady = registry.forUser(user.userId()).stream()
                    .anyMatch(record -> record.subscriptions().size() >= 2);
            boolean brokerReady = simpUserRegistry.findSubscriptions(subscription ->
                    user.userId().toString().equals(subscription.getSession().getUser().getName()))
                    .size() >= 2;
            if (appReady && brokerReady) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.forUser(user.userId()).stream()
                .anyMatch(record -> record.subscriptions().size() >= 2))
                .as("live subscriptions for %s", user.email())
                .isTrue();
        assertThat(simpUserRegistry.findSubscriptions(subscription ->
                user.userId().toString().equals(subscription.getSession().getUser().getName())))
                .as("broker subscriptions for %s", user.email())
                .hasSizeGreaterThanOrEqualTo(2);
    }

    private void awaitConnected(Connected connected) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String frame = connected.handler.frames.poll(remaining, TimeUnit.NANOSECONDS);
            if (frame == null) {
                break;
            }
            if (frame.startsWith("CONNECTED")) {
                return;
            }
            if (frame.startsWith("ERROR")) {
                throw new AssertionError("STOMP CONNECT was rejected: " + frame);
            }
        }
        throw new AssertionError("No STOMP CONNECTED frame arrived");
    }

    private Connected openSocket(WebSocketHttpHeaders headers) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler handler = new TestWebSocketHandler();
        URI uri = URI.create("ws://localhost:" + port + WS_ENDPOINT);
        try {
            WebSocketSession session = client.execute(handler, headers, uri)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return new Connected(session, handler);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private static WebSocketHttpHeaders handshakeHeaders(String cookie, String origin) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ORIGIN, origin);
        return headers;
    }

    private void send(
            WebSocketSession session,
            UUID conversationId,
            String content,
            UUID idempotencyKey) throws Exception {
        send(session, conversationId, content, idempotencyKey, "");
    }

    /**
     * Every fixture send carries an explicit idempotency key. {@code sentAt} is
     * deliberately never supplied by the client; it is derived by the server.
     */
    private void send(
            WebSocketSession session,
            UUID conversationId,
            String content,
            UUID idempotencyKey,
            String extraHeaders) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("content", content);
        payload.put("idempotencyKey", idempotencyKey.toString());
        sendRaw(session, objectMapper.writeValueAsString(payload), extraHeaders);
    }

    private static void sendRaw(
            WebSocketSession session, String body, String extraHeaders) throws Exception {
        session.sendMessage(new TextMessage(
                "SEND\ndestination:" + SEND_DESTINATION + "\ncontent-type:application/json\n"
                        + (extraHeaders == null ? "" : extraHeaders)
                        + "\n" + body + "\u0000"));
    }

    private static void send(WebSocketSession session, String frame) throws Exception {
        session.sendMessage(new TextMessage(frame));
    }

    private List<String> awaitRawFrames(Connected connected, Duration window)
            throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        List<String> frames = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String frame = connected.handler.frames.poll(remaining, TimeUnit.NANOSECONDS);
            if (frame == null) {
                break;
            }
            frames.add(frame);
        }
        return frames;
    }

    /** Parses the JSON body after the STOMP header/body blank-line separator. */
    private List<ChatFrame> awaitChatFrames(Connected connected, Duration window)
            throws InterruptedException {
        List<ChatFrame> frames = new ArrayList<>();
        for (String raw : awaitRawFrames(connected, window)) {
            JsonNode body = parseFrameBody(raw);
            if (body != null) {
                frames.add(new ChatFrame(raw, body));
            }
        }
        return frames;
    }

    private JsonNode parseFrameBody(String frame) {
        Matcher separator = STOMP_BODY_SEPARATOR.matcher(frame);
        if (!separator.find()) {
            return null;
        }
        String body = frame.substring(separator.end());
        int nul = body.indexOf('\0');
        if (nul >= 0) {
            body = body.substring(0, nul);
        }
        body = body.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(body.substring(start, end + 1));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static List<JsonNode> events(List<ChatFrame> frames, String type) {
        return frames.stream()
                .map(ChatFrame::json)
                .filter(event -> type.equals(event.path("type").asText()))
                .toList();
    }

    private static JsonNode singleEvent(List<ChatFrame> frames, String type) {
        List<JsonNode> matches = events(frames, type);
        assertThat(matches).as(type).hasSize(1);
        return matches.get(0);
    }

    private static boolean isAbsentOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void closeQuietly(Connected connected) {
        try {
            connected.session.close();
        } catch (IOException | RuntimeException ignored) {
            // The transport may already have been closed by the boundary.
        }
    }

    private Credentials newUserWithRole(String label, String roleName) throws Exception {
        Credentials user = newUser(label);
        UUID roleId = switch (roleName) {
            case "AGENT" -> UUID.fromString("00000000-0000-0000-0000-000000000002");
            case "ADMIN" -> UUID.fromString("00000000-0000-0000-0000-000000000003");
            default -> UUID.fromString("00000000-0000-0000-0000-000000000001");
        };
        jdbcTemplate.update("UPDATE users SET role_id = ? WHERE id = ?", roleId, user.userId());
        return reauthenticate(user);
    }

    /**
     * Keeps the generated local part below the RFC 5321 64-character limit that
     * Hibernate's email validator enforces, while retaining readable labels and
     * unique suffixes.
     */
    private static String emailFor(String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (cleaned.length() > 12) {
            cleaned = cleaned.substring(0, 12);
        }
        if (cleaned.isEmpty()) {
            cleaned = "u";
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "c74-" + cleaned + "-" + suffix + "@example.com";
    }

    private Credentials newUser(String label) throws Exception {
        String email = emailFor(label);
        String registerBody = """
                {"firstName":"Stage","lastName":"Seven","email":"%s","password":"%s","phone":"+919876543210"}
                """.formatted(email, PASSWORD);
        HttpResponse<String> registration = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/register"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(registerBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registration.statusCode())
                .as("registration response: %s", registration.body())
                .isEqualTo(201);

        Bootstrap bootstrap = anonymousBootstrap();
        trackSession(bootstrap.cookie());
        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);
        HttpResponse<String> login = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/login"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, bootstrap.cookie())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", bootstrap.csrfToken())
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(login.body());
        UUID userId = UUID.fromString(body.path("data").path("id").asText());
        String cookie = sessionCookie(login, bootstrap.cookie());
        createdUserIds.add(userId);
        trackSession(cookie);
        return new Credentials(
                userId,
                cookie,
                login.headers().firstValue("X-CSRF-TOKEN").orElseThrow(),
                email);
    }

    private Credentials reauthenticate(Credentials user) throws Exception {
        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(user.email(), PASSWORD);
        HttpResponse<String> login = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/login"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, user.cookie())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", user.csrfToken())
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = sessionCookie(login, user.cookie());
        trackSession(cookie);
        return new Credentials(
                user.userId(),
                cookie,
                login.headers().firstValue("X-CSRF-TOKEN").orElseThrow(),
                user.email());
    }

    private Bootstrap anonymousBootstrap() throws Exception {
        HttpResponse<String> bootstrap = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/csrf"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        return new Bootstrap(
                sessionCookie(bootstrap, null),
                bootstrap.headers().firstValue("X-CSRF-TOKEN").orElseThrow());
    }

    private URI apiUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void trackSession(String cookie) {
        if (cookie != null && cookie.startsWith("EV_SESSION=")) {
            String sessionId = cookie.substring("EV_SESSION=".length());
            int separator = sessionId.indexOf(';');
            if (separator >= 0) {
                sessionId = sessionId.substring(0, separator);
            }
            createdSessionIds.add(sessionId);
        }
    }

    private static String sessionCookie(HttpResponse<String> response, String fallback) {
        for (String cookie : response.headers().allValues("Set-Cookie")) {
            if (cookie.startsWith("EV_SESSION=")) {
                return cookie.substring(0, cookie.indexOf(';'));
            }
        }
        return fallback;
    }

    private record Bootstrap(String cookie, String csrfToken) {
    }

    private record Credentials(UUID userId, String cookie, String csrfToken, String email) {
    }

    private record Connected(WebSocketSession session, TestWebSocketHandler handler) {
    }

    private record Fixture(
            Credentials buyer, Credentials agent, UUID propertyId, UUID conversationId) {
    }

    private record MessageRow(
            UUID id,
            UUID conversationId,
            UUID senderId,
            String content,
            UUID idempotencyKey,
            Instant sentAt,
            String messageType) {
    }

    private record ChatFrame(String raw, JsonNode json) {
    }

    /** Deterministically pauses the real outbound channel after channel entry. */
    @TestConfiguration(proxyBeanMethods = false)
    static final class ControlledOutboundChannelConfig implements WebSocketMessageBrokerConfigurer {
        @Override
        public void configureClientOutboundChannel(ChannelRegistration registration) {
            registration.executor(ControlledOutboundExecutor.INSTANCE);
        }
    }

    /** Holds every event frame for the selected STOMP sessions at executor entry. */
    private static final class Gate {
        private static final Pattern EVENT_TYPE =
                Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");

        private final Set<String> stompSessionIds;
        private final Set<String> types;
        private final CountDownLatch queued;
        private final CountDownLatch release = new CountDownLatch(1);

        private Gate(int expectedFrames, Set<String> stompSessionIds, Set<String> types) {
            this.stompSessionIds = stompSessionIds;
            this.types = types;
            this.queued = new CountDownLatch(expectedFrames);
        }

        private boolean matches(Message<?> message) {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
            if (!stompSessionIds.contains(accessor.getSessionId())) {
                return false;
            }
            Matcher matcher = EVENT_TYPE.matcher(payloadText(message));
            return matcher.find() && (types == null || types.contains(matcher.group(1)));
        }

        private static String payloadText(Message<?> message) {
            Object payload = message.getPayload();
            if (payload instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return payload == null ? "" : payload.toString();
        }
    }

    /** Runs queued frames only after the test releases the armed gate. */
    private static final class ControlledOutboundExecutor implements TaskExecutor {
        private static final ControlledOutboundExecutor INSTANCE = new ControlledOutboundExecutor();

        private final AtomicReference<Gate> armed = new AtomicReference<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "controlled-outbound-dispatch");
            thread.setDaemon(true);
            return thread;
        });

        private void arm(Gate gate) {
            Gate previous = armed.getAndSet(gate);
            if (previous != null) {
                previous.release.countDown();
            }
        }

        private void disarm() {
            armed.set(null);
        }

        private void reset() {
            Gate gate = armed.getAndSet(null);
            if (gate != null) {
                gate.release.countDown();
            }
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (inFlight.get() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        @Override
        public void execute(Runnable command) {
            Gate gate = armed.get();
            if (gate != null
                    && command instanceof MessageHandlingRunnable runnable
                    && gate.matches(runnable.getMessage())) {
                gate.queued.countDown();
                submit(() -> {
                    try {
                        if (!gate.release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    command.run();
                });
                return;
            }
            submit(command);
        }

        private void submit(Runnable command) {
            inFlight.incrementAndGet();
            worker.execute(() -> {
                try {
                    command.run();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private static final class TestWebSocketHandler extends AbstractWebSocketHandler {
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            closed.complete(new CloseStatus(4003, exception.getClass().getSimpleName()));
        }
    }
}
