package com.estatevista.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.config.AppProperties;
import com.estatevista.config.WebSocketSecurityProperties;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Boundary;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Decision;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Frame;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Outcome;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

/** Deterministic unit coverage for the Stage 7.2 final outbound boundary. */
class WebSocketBoundaryUnitTest {

    private final WebSocketSecurityProperties properties = new WebSocketSecurityProperties();
    private final WebSocketConnectionRegistry registry = new WebSocketConnectionRegistry(properties);
    private final AuthoritativeSessionValidator validator = mock(AuthoritativeSessionValidator.class);
    private final WebSocketSessionRevocationService revocation =
            mock(WebSocketSessionRevocationService.class);
    private final AccountAuthorizationService accounts = mock(AccountAuthorizationService.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final WebSocketOutboundAuthorizationInterceptor interceptor =
            new WebSocketOutboundAuthorizationInterceptor(
                    registry, validator, revocation, accounts, audit);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final OutboundDispatchAuthorizationService authorization =
            new OutboundDispatchAuthorizationService(
                    registry, validator, accounts, conversations, messages, audit, objectMapper);

    @Test
    void revalidationIntervalAboveHardMaximumFailsClosed() {
        WebSocketSecurityProperties invalid = new WebSocketSecurityProperties();
        invalid.setRevalidationIntervalMs(WebSocketSecurityProperties.MAX_REVALIDATION_INTERVAL_MS + 1);
        assertThatThrownBy(invalid::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revalidation-interval-ms");
    }

    @Test
    void subscribedAllowedDestinationIsDispatched() {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/messages", "sub-1");
        Message<?> message = outboundMessage(fixture.stompSessionId(), "/user/queue/messages", "sub-1");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(revocation, never()).revokeConnection(any(
                WebSocketConnectionRegistry.ConnectionRecord.class), any());
    }

    @Test
    void resolvedUserQueueDestinationIsDispatchedForTheSameStompSession() {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/messages", "sub-1");
        Message<?> message = outboundMessage(
                fixture.stompSessionId(),
                "/queue/messages-" + fixture.stompSessionId(),
                "sub-1");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(revocation, never()).revokeConnection(any(
                WebSocketConnectionRegistry.ConnectionRecord.class), any());
    }

    @Test
    void connectInstallsTrustedHandshakePrincipalOnTheStompSession() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(userId.toString());
        when(authentication.isAuthenticated()).thenReturn(true);
        when(accounts.resolve(authentication)).thenReturn(Optional.of(
                AuthenticatedAccount.of(userId, PlatformRole.BUYER, AccountStatus.ACTIVE)));
        when(validator.csrfToken("spring-session")).thenReturn("current-token");
        when(validator.validate("spring-session", userId.toString(), "spring-session", false))
                .thenReturn(AuthoritativeSessionValidator.ValidationResult.ok(PlatformRole.BUYER));

        WebSocketConnectionRegistry localRegistry =
                new WebSocketConnectionRegistry(new WebSocketSecurityProperties());
        WebSocketConnectionRegistry.ConnectionRecord record = localRegistry.registerPending(
                "spring-session", userId, authentication, PlatformRole.BUYER, "127.0.0.1");
        AppProperties appProperties = new AppProperties(
                new AppProperties.Cors(List.of()),
                new AppProperties.Websocket("/ws", List.of("http://localhost:5500")));
        WebSocketSecurityInterceptor inbound = new WebSocketSecurityInterceptor(
                appProperties,
                new WebSocketSecurityProperties(),
                validator,
                localRegistry,
                revocation,
                mock(WebSocketRateLimitService.class),
                accounts,
                audit);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("stomp-principal");
        accessor.setNativeHeader("X-CSRF-TOKEN", "current-token");
        accessor.setSessionAttributes(Map.of(
                WebSocketSessionAttributes.HANDSHAKE_ID, record.connectionId(),
                WebSocketSessionAttributes.ORIGIN, "http://localhost:5500"));
        accessor.setLeaveMutable(true);
        Message<?> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = inbound.preSend(connect, channel);

        assertThat(result).isNotNull();
        assertThat(StompHeaderAccessor.wrap(result).getUser()).isSameAs(authentication);
        assertThat(localRegistry.byStompSession("stomp-principal")).isSameAs(record);
    }

    @Test
    void frameWithoutMatchingSubscriptionIsDropped() {
        Fixture fixture = fixture();
        Message<?> message = outboundMessage(fixture.stompSessionId(), "/user/queue/messages", "sub-9");

        assertThat(interceptor.preSend(message, channel)).isNull();
        verify(audit).recordWebSocketSecurityEvent(
                eq(fixture.userId()), eq(AuditEventService.ACTION_WS_AUTH_FAILURE),
                eq("OUTBOUND_SUBSCRIPTION_MISSING"), eq("/user/queue/messages"), any());
    }

    @Test
    void unauthorizedDestinationIsDropped() {
        Fixture fixture = fixture();
        Message<?> message = outboundMessage(fixture.stompSessionId(), "/topic/property-updates", "sub-2");

        assertThat(interceptor.preSend(message, channel)).isNull();
        verify(audit).recordWebSocketSecurityEvent(
                eq(fixture.userId()), eq(AuditEventService.ACTION_WS_AUTH_FAILURE),
                eq("OUTBOUND_DESTINATION_DENIED"), eq("/topic/property-updates"), any());
    }

    @Test
    void finalTransportBoundaryAllowsSubscribedNotificationQueueFrameWithoutChatLookups() {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/notifications", "sub-notes");
        Frame frame = new Frame(
                "/user/queue/notifications", "sub-notes", "{\"type\":\"NEW_MESSAGE\"}");

        Decision decision = authorization.authorize(Boundary.TRANSPORT, fixture.record(), frame);

        assertThat(decision.outcome()).isEqualTo(Outcome.ALLOW);
        verify(conversations, never()).findParticipantConversation(any(), any(), anyBoolean());
        verify(messages, never()).findByConversationIdAndId(any(), any());
    }

    @Test
    void notificationQueueFramesUseBoundarySpecificDropReasons() {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/notifications", "sub-notes");
        Frame frame = new Frame(
                "/user/queue/notifications", "sub-notes", "{\"type\":\"NEW_MESSAGE\"}");

        assertThat(authorization.authorize(Boundary.CHANNEL, fixture.record(), frame).reason())
                .isNull();
        registry.removeSubscription(fixture.record(), "sub-notes");

        Decision transport = authorization.authorize(Boundary.TRANSPORT, fixture.record(), frame);
        assertThat(transport.outcome()).isEqualTo(Outcome.DROP);
        assertThat(transport.reason()).isEqualTo("TRANSPORT_SUBSCRIPTION_MISSING");
        verify(audit).recordWebSocketSecurityEvent(
                eq(fixture.userId()), eq(AuditEventService.ACTION_WS_AUTH_FAILURE),
                eq("TRANSPORT_SUBSCRIPTION_MISSING"), eq("/user/queue/notifications"), any());
    }

    @Test
    void invalidatedSessionRevokesTransportAndDropsFrame() {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/messages", "sub-1");
        when(validator.validate(any(), any(), any(), eq(false))).thenReturn(
                AuthoritativeSessionValidator.ValidationResult.invalid(
                        AuthoritativeSessionValidator.Reason.MISSING_SESSION));
        Message<?> message = outboundMessage(fixture.stompSessionId(), "/user/queue/messages", "sub-1");

        assertThat(interceptor.preSend(message, channel)).isNull();
        verify(revocation).revokeConnection(fixture.record(), "AUTHORIZATION_LOSS");
    }

    @Test
    void revocationMarksClosedBeforeTransportCloseAndConcurrentDispatchIsDropped() throws Exception {
        Fixture fixture = fixture();
        registry.addSubscription(fixture.record(), "/user/queue/messages", "sub-1");
        java.util.concurrent.atomic.AtomicReference<WebSocketConnectionRegistry.State> stateAtClose =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            stateAtClose.set(fixture.record().state());
            return null;
        }).when(fixture.transport()).close(any(CloseStatus.class));

        WebSocketSessionRevocationService realRevocation =
                new WebSocketSessionRevocationService(registry, validator, audit, properties);
        WebSocketOutboundAuthorizationInterceptor realOutbound =
                new WebSocketOutboundAuthorizationInterceptor(
                        registry, validator, realRevocation, accounts, audit);
        Message<?> message = outboundMessage(fixture.stompSessionId(), "/user/queue/messages", "sub-1");

        assertThat(realRevocation.revokeConnection(
                fixture.record(), WebSocketSessionRevocationService.REASON_LOGOUT)).isTrue();
        assertThat(stateAtClose.get()).isEqualTo(WebSocketConnectionRegistry.State.CLOSED);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    release.await();
                    return realOutbound.preSend(message, channel) != null;
                }));
            }
            release.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(fixture.record().state()).isEqualTo(WebSocketConnectionRegistry.State.CLOSED);
    }

    private Fixture fixture() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(userId.toString());
        when(authentication.isAuthenticated()).thenReturn(true);
        when(accounts.resolve(authentication)).thenReturn(Optional.of(
                AuthenticatedAccount.of(userId, PlatformRole.BUYER, AccountStatus.ACTIVE)));
        when(validator.validate(any(), any(), any(), eq(false))).thenReturn(
                AuthoritativeSessionValidator.ValidationResult.ok(PlatformRole.BUYER));

        WebSocketConnectionRegistry.ConnectionRecord record = registry.registerPending(
                "spring-session", userId, authentication, PlatformRole.BUYER, "127.0.0.1");
        assertThat(record).isNotNull();
        WebSocketSession transport = mock(WebSocketSession.class);
        when(transport.isOpen()).thenReturn(true);
        registry.attachTransport(record.connectionId(), transport);
        registry.activate(record.connectionId(), "stomp-session", PlatformRole.BUYER);
        return new Fixture(record, userId, "stomp-session", transport);
    }

    private static Message<?> outboundMessage(String stompSessionId, String destination, String subscriptionId) {
        SimpMessageHeaderAccessor accessor =
                SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(stompSessionId);
        accessor.setDestination(destination);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private record Fixture(
            WebSocketConnectionRegistry.ConnectionRecord record,
            UUID userId,
            String stompSessionId,
            WebSocketSession transport) {
    }
}
