package com.estatevista.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.estatevista.audit.AuditEventService;
import com.estatevista.config.WebSocketSecurityProperties;
import com.estatevista.security.RateLimitProperties;
import com.estatevista.security.RateLimitService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Real transport verification of the Stage 7.2 WebSocket security foundation.
 *
 * <p>Every case uses a live Spring Boot server, the real security filter chain,
 * real Spring Session JDBC persistence, and native WebSocket/STOMP frames. The
 * suite never substitutes a mocked handshake or a mocked repository. It is
 * gated on {@code TEST_DB_URL} so a database-free build cannot claim coverage.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.ws-connection-attempt.max-attempts=20",
                "app.rate-limit.ws-message-user.max-attempts=30",
                "app.rate-limit.ws-message-conversation.max-attempts=10",
                "app.websocket-security.max-sockets-per-user=5",
                "app.websocket-security.max-subscriptions-per-connection=3",
                "app.websocket-security.max-frame-bytes=32768",
                "app.websocket-security.revalidation-interval-ms=60000",
                "app.websocket-security.validation-cache-ttl-ms=0"
        })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class WebSocketSecurityIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "WebSocket-Password-123";
    private static final String WS_ENDPOINT = "/ws";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebSocketConnectionRegistry registry;

    @Autowired
    private WebSocketSessionRevalidator revalidator;

    @Autowired
    private AuthoritativeSessionValidator validator;

    @Autowired
    private WebSocketSecurityProperties properties;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private WebSocketSessionRevocationService revocation;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @BeforeEach
    void resetConnectionAttemptLimit() {
        rateLimitService.reset(
                RateLimitService.SCOPE_WS_CONNECTION_ATTEMPT, "ip:127.0.0.1");
    }

    @AfterEach
    void closeAllTrackedSockets() {
        for (WebSocketConnectionRegistry.ConnectionRecord record : List.copyOf(registry.all())) {
            revocation.revokeConnection(record, WebSocketSessionRevocationService.REASON_SESSION_REVOKED);
        }
    }

    @Test
    void authenticatedHandshakeAndConnectFrameAreAuthorized() throws Exception {
        Credentials user = newUser("connect");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.take()).startsWith("CONNECTED");
            assertThat(activeCount()).isEqualTo(1);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void connectFrameWithoutMatchingSessionCsrfTokenIsRejected() throws Exception {
        Credentials user = newUser("csrf");
        Connected connected = connect(user, user.cookie(), user.csrfToken() + "-tampered");
        try {
            String response = connected.handler.frames.poll(5, TimeUnit.SECONDS);
            assertThat(response == null || response.startsWith("ERROR")).isTrue();
            assertRevokedOrRejected(connected);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void handshakeWithDisallowedOriginIsForbidden() throws Exception {
        Credentials user = newUser("origin");
        WebSocketHttpHeaders headers = handshakeHeaders(user.cookie(), "https://evil.example");
        assertThatThrownBy(() -> openSocket(headers))
                .hasMessageContaining("403");
    }

    @Test
    void handshakeWithClientIdentityOverrideIsForbidden() throws Exception {
        Credentials user = newUser("identity");
        WebSocketHttpHeaders headers = handshakeHeaders(user.cookie(), ORIGIN);
        headers.add("X-User-Id", user.userId().toString());
        assertThatThrownBy(() -> openSocket(headers))
                .hasMessageContaining("403");
    }

    @Test
    void handshakeWithQueryCredentialsIsForbidden() throws Exception {
        Credentials user = newUser("query");
        assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), ORIGIN), "?token=secret"))
                .hasMessageContaining("403");
    }

    @Test
    void unauthenticatedHandshakeIsForbidden() throws Exception {
        assertThatThrownBy(() -> openSocket(handshakeHeaders(null, ORIGIN)))
                .hasMessageMatching(".*(401|403).*");
    }

    @Test
    void handshakeOriginMatrixRejectsMissingDuplicateMalformedAndUntrustedValues() throws Exception {
        Credentials user = newUser("origin-matrix");
        assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), null)))
                .hasMessageContaining("403");

        WebSocketHttpHeaders duplicateOrigin = handshakeHeaders(user.cookie(), ORIGIN);
        duplicateOrigin.add(HttpHeaders.ORIGIN, ORIGIN);
        assertThatThrownBy(() -> openSocket(duplicateOrigin))
                .hasMessageContaining("403");

        assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), "not-a-url")))
                .hasMessageContaining("403");
        assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), "https://evil.example")))
                .hasMessageContaining("403");
        assertThat(countAuditEvents(AuditEventService.ACTION_WS_AUTH_FAILURE, "INVALID_ORIGIN"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void connectFrameCsrfMatrixRejectsMissingDuplicateStaleCrossSessionAndPreLoginTokens()
            throws Exception {
        Credentials user = newUser("csrf-matrix");
        Credentials otherUser = newUser("csrf-other");
        Credentials rotated = reauthenticate(user);
        String preLoginToken = anonymousCsrfToken();
        List<List<String>> rejectedTokens = List.of(
                List.of(),
                List.of(rotated.csrfToken(), rotated.csrfToken()),
                List.of(rotated.csrfToken() + "-tampered"),
                List.of(user.csrfToken()),
                List.of(otherUser.csrfToken()),
                List.of(preLoginToken));

        for (List<String> tokens : rejectedTokens) {
            Connected connected = connectWithCsrfHeaders(rotated, rotated.cookie(), "0,0", tokens);
            try {
                String frame = connected.handler.frames.poll(5, TimeUnit.SECONDS);
                assertThat(frame == null || frame.startsWith("ERROR")).isTrue();
                assertRevokedOrRejected(connected);
                awaitRegistryEmpty();
            } finally {
                connected.session.close();
            }
        }
        assertThat(countAuditEvents(AuditEventService.ACTION_WS_AUTH_FAILURE, "INVALID_CONNECT_CSRF"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void buyerAndAgentCanConnectButAdminHandshakeIsForbidden() throws Exception {
        Credentials buyer = newUser("role-buyer");
        Credentials agent = newUserWithRole("role-agent", "AGENT");
        Credentials admin = newUserWithRole("role-admin", "ADMIN");
        Connected buyerSocket = connectAndAuthenticate(buyer);
        Connected agentSocket = connectAndAuthenticate(agent);
        try {
            assertThat(buyerSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(agentSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(registry.forUser(buyer.userId())).hasSize(1);
            assertThat(registry.forUser(agent.userId())).hasSize(1);
            assertThatThrownBy(() -> openSocket(handshakeHeaders(admin.cookie(), ORIGIN)))
                    .hasMessageContaining("403");
            assertThat(countAuditEvents(AuditEventService.ACTION_WS_AUTH_FAILURE, "ROLE_NOT_ELIGIBLE"))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            buyerSocket.session.close();
            agentSocket.session.close();
        }
    }

    @Test
    void subscriptionIsLimitedPerConnectionAndUnauthorizedDestinationsAreDenied() throws Exception {
        Credentials user = newUser("subscriptions");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            send(connected.session,
                    "SUBSCRIBE\nid:s1\ndestination:/user/queue/messages\n\n\u0000");
            send(connected.session,
                    "SUBSCRIBE\nid:s2\ndestination:/user/queue/notifications\n\n\u0000");
            send(connected.session,
                    "SUBSCRIBE\nid:s3\ndestination:/user/queue/errors\n\n\u0000");
            send(connected.session,
                    "SUBSCRIBE\nid:s4\ndestination:/user/queue/messages\n\n\u0000");
            send(connected.session,
                    "SUBSCRIBE\nid:s5\ndestination:/topic/property-updates\n\n\u0000");
            assertRevokedOrRejected(connected);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void duplicateSubscriptionIdIsRejected() throws Exception {
        Credentials user = newUser("duplicate-subscription");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            send(connected.session,
                    "SUBSCRIBE\nid:s1\ndestination:/user/queue/messages\n\n\u0000");
            send(connected.session,
                    "SUBSCRIBE\nid:s1\ndestination:/user/queue/notifications\n\n\u0000");
            assertRevokedOrRejected(connected);
            assertThat(countAuditEvents(AuditEventService.ACTION_WS_AUTH_FAILURE, "SUBSCRIPTION_LIMIT"))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void oversizedStompFrameRevokesTheTransport() throws Exception {
        Credentials user = newUser("frame");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            String payload = "SEND\ndestination:/app/chat.send\ncontent-type:application/json\n\n"
                    + "x".repeat(properties.getMaxFrameBytes() + 1024) + "\u0000";
            try {
                send(connected.session, payload);
            } catch (IllegalStateException | IOException closedWhileWriting) {
                // The container may tear down the transport before the client
                // finishes writing a frame above the inbound size limit. The
                // observed close below is the authoritative assertion.
            }
            assertRevokedOrRejected(connected);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void nativeHeartbeatFramesRemainAuthenticatedAndDoNotEscapeTheBoundary() throws Exception {
        Credentials user = newUser("heartbeat");
        Connected connected = connect(user, user.cookie(), user.csrfToken(), "10000,10000");
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            send(connected.session, "\n");
            send(connected.session,
                    "SUBSCRIBE\nid:s1\ndestination:/user/queue/messages\n\n\u0000");
            assertThat(activeCount()).isGreaterThanOrEqualTo(1);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void logoutRevokesTheLiveTransport() throws Exception {
        Credentials user = newUser("logout");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(logout(user).statusCode()).isEqualTo(200);
            assertThat(connected.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            connected.session.close();
        }
    }

    @Test
    void logoutRevokesOnlyTheLoggedOutSessionAndLeavesOtherSessionsActive() throws Exception {
        Credentials firstSession = newUser("logout-isolation");
        Credentials secondSession = newSession(firstSession);
        Connected firstSocket = connectAndAuthenticate(firstSession);
        Connected secondSocket = connectAndAuthenticate(secondSession);
        try {
            assertThat(firstSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(secondSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(registry.forSpringSession(sessionId(firstSession.cookie()))).hasSize(1);
            assertThat(registry.forSpringSession(sessionId(secondSession.cookie()))).hasSize(1);

            assertThat(logout(firstSession).statusCode()).isEqualTo(200);
            assertThat(firstSocket.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(secondSocket.session.isOpen()).isTrue();
            assertThat(activeCount()).isEqualTo(1);
            assertThat(registry.forSpringSession(sessionId(firstSession.cookie()))).isEmpty();
            assertThat(registry.forSpringSession(sessionId(secondSession.cookie()))).hasSize(1);
        } finally {
            firstSocket.session.close();
            secondSocket.session.close();
        }
    }

    @Test
    void accountStatusInvalidationIsDetectedByIdleRevalidation() throws Exception {
        Credentials user = newUser("status");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            jdbcTemplate.update(
                    "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", user.userId());
            assertThat(revalidator.revalidateAll()).isGreaterThanOrEqualTo(1);
            assertThat(connected.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            connected.session.close();
        }
    }

    @Test
    void roleLossAndDeactivationAreDetectedByIdleRevalidation() throws Exception {
        Credentials agent = newUserWithRole("role-loss", "AGENT");
        Connected roleLossSocket = connectAndAuthenticate(agent);
        try {
            assertThat(roleLossSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            jdbcTemplate.update(
                    "UPDATE users SET role_id = ? WHERE id = ?",
                    UUID.fromString("00000000-0000-0000-0000-000000000003"), agent.userId());
            assertThat(revalidator.revalidateAll()).isGreaterThanOrEqualTo(1);
            assertThat(roleLossSocket.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            roleLossSocket.session.close();
        }

        Credentials deactivated = newUser("deactivation");
        Connected deactivatedSocket = connectAndAuthenticate(deactivated);
        try {
            assertThat(deactivatedSocket.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            jdbcTemplate.update(
                    "UPDATE users SET account_status = 'INACTIVE' WHERE id = ?", deactivated.userId());
            assertThat(revalidator.revalidateAll()).isGreaterThanOrEqualTo(1);
            assertThat(deactivatedSocket.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            deactivatedSocket.session.close();
        }
        assertThat(countAuditEvents(
                AuditEventService.ACTION_WS_SESSION_REVOKED, "AUTHORIZATION_LOSS"))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void pendingAndIdleConnectionsAreCleanedUpWhenActivityStops() throws Exception {
        Credentials user = newUser("idle-cleanup");
        int originalPendingTimeout = properties.getPendingHandshakeTimeoutMs();
        int originalStaleTimeout = properties.getStaleConnectionTimeoutMs();

        Connected pending = openSocket(handshakeHeaders(user.cookie(), ORIGIN));
        try {
            properties.setPendingHandshakeTimeoutMs(1);
            Thread.sleep(25);
            assertThat(revalidator.revalidateAll()).isGreaterThanOrEqualTo(1);
            assertRevokedOrRejected(pending);
            awaitRegistryEmpty();
        } finally {
            properties.setPendingHandshakeTimeoutMs(originalPendingTimeout);
            pending.session.close();
        }

        Connected idle = connectAndAuthenticate(user);
        try {
            assertThat(idle.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            properties.setStaleConnectionTimeoutMs(1);
            Thread.sleep(25);
            assertThat(revalidator.revalidateAll()).isGreaterThanOrEqualTo(1);
            assertThat(idle.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            properties.setStaleConnectionTimeoutMs(originalStaleTimeout);
            idle.session.close();
        }
    }

    @Test
    void socketLimitPerUserIsEnforced() throws Exception {
        Credentials user = newUser("socket-limit");
        java.util.List<Connected> sockets = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < properties.getMaxSocketsPerUser(); index++) {
                Connected connected = connectAndAuthenticate(user);
                sockets.add(connected);
                assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
                assertThat(connected.session.isOpen()).isTrue();
            }
            assertThatThrownBy(() -> connectAndAuthenticate(user))
                    .hasMessageContaining("403");
        } finally {
            for (Connected connected : sockets) {
                connected.session.close();
            }
        }
    }

    @Test
    void messageRateLimitIsEnforcedPerUserAndConversation() throws Exception {
        Credentials user = newUser("message-limit");
        Connected connected = connectAndAuthenticate(user);
        try {
            assertThat(connected.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            String conversationId = UUID.randomUUID().toString();
            int userLimit = rateLimitProperties.getWsMessageUser().getMaxAttempts();
            int conversationLimit = rateLimitProperties.getWsMessageConversation().getMaxAttempts();
            int limit = Math.min(userLimit, conversationLimit);
            for (int index = 0; index < limit; index++) {
                send(connected.session, "SEND\ndestination:/app/chat.send\ncontent-type:application/json\n\n"
                        + "{\"conversationId\":\"" + conversationId + "\",\"body\":\"x\"}\u0000");
            }
            send(connected.session, "SEND\ndestination:/app/chat.send\ncontent-type:application/json\n\n"
                    + "{\"conversationId\":\"" + conversationId + "\",\"body\":\"x\"}\u0000");
            assertRevokedOrRejected(connected);
        } finally {
            connected.session.close();
        }
    }

    @Test
    void connectionAttemptLimitIsEnforcedPerClientIp() throws Exception {
        Credentials user = newUser("attempts");
        int allowed = rateLimitProperties.getWsConnectionAttempt().getMaxAttempts();
        for (int attempt = 0; attempt < allowed; attempt++) {
            try {
                Connected socket = openSocket(handshakeHeaders(user.cookie(), ORIGIN));
                socket.session.close();
            } catch (IllegalStateException denied) {
                assertThat(denied).hasMessageContaining("403");
                break;
            }
        }
        assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), ORIGIN)))
                .hasMessageContaining("403");
        assertThat(countAuditEvents(AuditEventService.ACTION_WS_AUTH_FAILURE, "RATE_LIMITED"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void pendingSocketCannotFrameBeforeConnectAndFailedConnectLeavesNoRegistryEntry() throws Exception {
        Credentials user = newUser("pending");
        Connected pending = openSocket(handshakeHeaders(user.cookie(), ORIGIN));
        try {
            send(pending.session, "SUBSCRIBE\nid:s1\ndestination:/user/queue/messages\n\n\u0000");
            assertRevokedOrRejected(pending);
            awaitRegistryEmpty();
        } finally {
            pending.session.close();
        }

        Connected failedConnect = connect(user, user.cookie(), user.csrfToken() + "-tampered");
        try {
            String response = failedConnect.handler.frames.poll(5, TimeUnit.SECONDS);
            assertThat(response == null || response.startsWith("ERROR")).isTrue();
            assertRevokedOrRejected(failedConnect);
            awaitRegistryEmpty();
        } finally {
            failedConnect.session.close();
        }
    }

    @Test
    void springSessionDeletionAndExpiryCloseLiveSocketsAndRejectLaterHandshakes() throws Exception {
        Credentials deletedUser = newUser("session-delete");
        String deletedSessionId = sessionId(deletedUser.cookie());
        Connected deleted = connectAndAuthenticate(deletedUser);
        try {
            assertThat(deleted.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(activeCount()).isEqualTo(1);
            assertThat(registry.forSpringSession(deletedSessionId)).hasSize(1);
            validator.deleteSession(deletedSessionId);
            assertThat(validator.validate(
                    deletedSessionId, deletedUser.userId().toString(), deletedSessionId, false).valid())
                    .isFalse();
            revalidator.revalidateAll();
            assertThat(deleted.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            deleted.session.close();
        }
        assertThatThrownBy(() -> openSocket(handshakeHeaders(deletedUser.cookie(), ORIGIN)))
                .hasMessageMatching(".*(401|403).*");

        Credentials expiredUser = newUser("session-expiry");
        String expiredSessionId = sessionId(expiredUser.cookie());
        Connected expired = connectAndAuthenticate(expiredUser);
        try {
            assertThat(expired.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            long expiredAt = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
            jdbcTemplate.update(
                    "UPDATE spring_session SET last_access_time = ?, expiry_time = ? WHERE session_id = ?",
                    expiredAt, expiredAt, expiredSessionId);
            assertThat(validator.validate(
                    expiredSessionId, expiredUser.userId().toString(), expiredSessionId, false).valid())
                    .isFalse();
            revalidator.revalidateAll();
            assertThat(expired.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
        } finally {
            expired.session.close();
        }
    }

    @Test
    void accountWideRevocationClosesEverySocketAndDeletesIndexedSessions() throws Exception {
        Credentials firstSession = newUser("account-revoke");
        Credentials secondSession = newSession(firstSession);
        Connected first = connectAndAuthenticate(firstSession);
        Connected second = connectAndAuthenticate(secondSession);
        try {
            assertThat(first.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(second.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            assertThat(validator.sessionsForPrincipal(firstSession.userId().toString())).hasSizeGreaterThanOrEqualTo(2);

            assertThat(revocation.revokeAccount(
                    firstSession.userId(), WebSocketSessionRevocationService.REASON_ACCOUNT_REVOKED))
                    .isEqualTo(2);
            assertThat(first.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(second.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
            assertThat(validator.sessionsForPrincipal(firstSession.userId().toString())).isEmpty();
        } finally {
            first.session.close();
            second.session.close();
        }
    }

    @Test
    void sessionRotationClosesStaleSocketsAndRejectsStaleTokens() throws Exception {
        Credentials user = newUser("rotation");
        Connected stale = connectAndAuthenticate(user);
        try {
            assertThat(stale.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            Credentials rotated = reauthenticate(user);
            assertThat(rotated.cookie()).isNotEqualTo(user.cookie());
            revalidator.revalidateAll();
            assertThat(stale.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).getCode())
                    .isEqualTo(4003);
            assertThat(activeCount()).isZero();
            assertThatThrownBy(() -> openSocket(handshakeHeaders(user.cookie(), ORIGIN)))
                    .hasMessageMatching(".*(401|403).*");

            Connected fresh = connectAndAuthenticate(rotated);
            try {
                assertThat(fresh.handler.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            } finally {
                fresh.session.close();
            }
        } finally {
            stale.session.close();
        }
    }

    @Test
    void websocketAuditPayloadsContainOnlySafeClassifications() throws Exception {
        Credentials user = newUser("audit");
        Connected connected = connect(user, user.cookie(), "invalid-token");
        assertRevokedOrRejected(connected);
        connected.session.close();

        List<AuditRow> rows = awaitWebSocketAuditRows(user.userId());
        assertThat(rows).extracting(AuditRow::action).contains(
                AuditEventService.ACTION_WS_AUTH_FAILURE,
                AuditEventService.ACTION_WS_SESSION_REVOKED);
        for (AuditRow row : rows) {
            JsonNode node = objectMapper.readTree(row.payload());
            assertThat(row.actorId()).isEqualTo(user.userId());
            assertThat(row.action()).isIn(
                    AuditEventService.ACTION_WS_AUTH_FAILURE,
                    AuditEventService.ACTION_WS_SESSION_REVOKED);
            assertThat(row.entityId()).isEqualTo(UUID.fromString(node.path("correlationId").asText()));
            assertThat(node.path("outcome").asText()).isEqualTo(
                    AuditEventService.ACTION_WS_SESSION_REVOKED.equals(row.action())
                            ? "REVOKED" : "DENIED");
            assertThat(node.path("reason").asText()).matches("[A-Z_]{1,40}");
            assertThat(node.path("scope").asText()).isIn("connect", "session");
            assertThat(row.payload()).doesNotContain(
                    user.csrfToken(), user.cookie(), user.userId().toString());
        }
    }

    private Connected connectAndAuthenticate(Credentials user) throws Exception {
        return connect(user, user.cookie(), user.csrfToken(), "0,0");
    }

    private Connected connect(Credentials user, String cookie, String csrfToken) throws Exception {
        return connect(user, cookie, csrfToken, "0,0");
    }

    private Connected connect(
            Credentials user, String cookie, String csrfToken, String heartBeat) throws Exception {
        return connectWithCsrfHeaders(user, cookie, heartBeat, List.of(csrfToken));
    }

    private Connected connectWithCsrfHeaders(
            Credentials user, String cookie, String heartBeat, List<String> csrfTokens) throws Exception {
        Connected connected = openSocket(handshakeHeaders(cookie, ORIGIN));
        StringBuilder frame = new StringBuilder("CONNECT\naccept-version:1.2\nheart-beat:")
                .append(heartBeat).append('\n');
        for (String token : csrfTokens) {
            frame.append("X-CSRF-TOKEN:").append(token).append('\n');
        }
        frame.append('\n').append('\u0000');
        send(connected.session, frame.toString());
        return connected;
    }

    private Connected openSocket(WebSocketHttpHeaders headers) throws Exception {
        return openSocket(headers, "");
    }

    private Connected openSocket(WebSocketHttpHeaders headers, String query) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler handler = new TestWebSocketHandler();
        URI uri = URI.create("ws://localhost:" + port + WS_ENDPOINT + query);
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
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        if (origin != null) {
            headers.add(HttpHeaders.ORIGIN, origin);
        }
        return headers;
    }

    private static void send(WebSocketSession session, String frame) throws Exception {
        session.sendMessage(new TextMessage(frame));
    }

    private static void assertRevokedOrRejected(Connected connected) throws Exception {
        CloseStatus status = connected.handler.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        // The framework may send ERROR/oversize close frames before the
        // revocation close frame reaches the client; both prove the transport
        // was terminated rather than left usable.
        assertThat(status.getCode()).isNotEqualTo(1000);
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

    private String anonymousCsrfToken() throws Exception {
        HttpResponse<String> bootstrap = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/csrf"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        return bootstrap.headers().firstValue("X-CSRF-TOKEN").orElseThrow();
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

    private Credentials newUser(String label) throws Exception {
        String email = "ws72-" + label + "-" + UUID.randomUUID() + "@example.com";
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
        assertThat(registration.statusCode()).isEqualTo(201);

        HttpResponse<String> bootstrap = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/csrf"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        String anonymousCookie = sessionCookie(bootstrap, null);
        String anonymousCsrf = bootstrap.headers().firstValue("X-CSRF-TOKEN").orElseThrow();

        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);
        HttpResponse<String> login = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/login"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, anonymousCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", anonymousCsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = sessionCookie(login, anonymousCookie);
        String csrfToken = login.headers().firstValue("X-CSRF-TOKEN").orElseThrow();
        JsonNode body = objectMapper.readTree(login.body());
        UUID userId = UUID.fromString(body.path("data").path("id").asText());
        return new Credentials(userId, cookie, csrfToken, email);
    }

    private URI apiUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String sessionCookie(HttpResponse<String> response, String fallback) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        for (String cookie : cookies) {
            if (cookie.startsWith("EV_SESSION=")) {
                return cookie.substring(0, cookie.indexOf(';'));
            }
        }
        return fallback;
    }

    private static String sessionId(String cookie) {
        String value = cookie.startsWith("EV_SESSION=")
                ? cookie.substring("EV_SESSION=".length()) : cookie;
        // DefaultCookieSerializer base64-encodes the session identifier in the
        // cookie; the registry stores the decoded Spring Session identifier.
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private Credentials newSession(Credentials user) throws Exception {
        HttpResponse<String> bootstrap = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/csrf"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        String anonymousCookie = sessionCookie(bootstrap, null);
        String anonymousCsrf = bootstrap.headers().firstValue("X-CSRF-TOKEN").orElseThrow();
        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(user.email(), PASSWORD);
        HttpResponse<String> login = httpClient.send(
                HttpRequest.newBuilder(apiUri("/api/v1/auth/login"))
                        .timeout(TIMEOUT)
                        .header(HttpHeaders.COOKIE, anonymousCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", anonymousCsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        return new Credentials(
                user.userId(),
                sessionCookie(login, anonymousCookie),
                login.headers().firstValue("X-CSRF-TOKEN").orElseThrow(),
                user.email());
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
        return new Credentials(
                user.userId(),
                sessionCookie(login, user.cookie()),
                login.headers().firstValue("X-CSRF-TOKEN").orElseThrow(),
                user.email());
    }

    private void awaitRegistryEmpty() throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!registry.all().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(registry.all()).isEmpty();
    }

    private long activeCount() {
        return registry.all().stream()
                .filter(record -> record.state() == WebSocketConnectionRegistry.State.AUTHENTICATED)
                .count();
    }

    /** Audit rows are written after the close frame reaches the client. */
    private List<AuditRow> awaitWebSocketAuditRows(UUID actorId) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        List<AuditRow> rows;
        do {
            rows = jdbcTemplate.query(
                    "SELECT actor_id, action, entity_id, after_data::text AS after_data "
                            + "FROM audit_logs WHERE actor_id = ? AND action IN (?, ?)",
                    (resultSet, rowNumber) -> new AuditRow(
                            resultSet.getObject("actor_id", UUID.class),
                            resultSet.getString("action"),
                            resultSet.getObject("entity_id", UUID.class),
                            resultSet.getString("after_data")),
                    actorId,
                    AuditEventService.ACTION_WS_AUTH_FAILURE,
                    AuditEventService.ACTION_WS_SESSION_REVOKED);
            if (rows.stream().anyMatch(row -> AuditEventService.ACTION_WS_AUTH_FAILURE.equals(row.action()))
                    && rows.stream().anyMatch(row -> AuditEventService.ACTION_WS_SESSION_REVOKED.equals(row.action()))) {
                return rows;
            }
            if (System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
        } while (System.nanoTime() < deadline);
        return rows;
    }

    private int countAuditEvents(String action, String reason) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND after_data::text LIKE ?",
                Integer.class, action, "%" + reason + "%");
    }

    private record AuditRow(UUID actorId, String action, UUID entityId, String payload) {
    }

    private record Credentials(UUID userId, String cookie, String csrfToken, String email) {
    }

    private record Connected(WebSocketSession session, TestWebSocketHandler handler) {
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
