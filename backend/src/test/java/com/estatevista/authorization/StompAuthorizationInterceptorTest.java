package com.estatevista.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.entity.AccountStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Verifies the STOMPCONNECT/SUBSCRIBE/SEND authorization boundary. */
class StompAuthorizationInterceptorTest {

    private final AccountAuthorizationService accounts = mock(AccountAuthorizationService.class);
    private final ResourceAuthorizationService resources = mock(ResourceAuthorizationService.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    private final UUID userId = UUID.randomUUID();
    private final Authentication principal = new SessionAuthenticationToken(
            userId.toString(), List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    private final AuthenticatedAccount account =
            AuthenticatedAccount.of(userId, PlatformRole.BUYER, AccountStatus.ACTIVE);

    private StompAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthorizationInterceptor(accounts, resources);
        when(accounts.requireActivePrincipal(principal)).thenReturn(account);
    }

    @Test
    void connectRequiresAnAuthenticatedSessionPrincipal() {
        assertThat(interceptor.preSend(message(StompCommand.CONNECT, "", null), channel)).isNotNull();

        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        when(accounts.requireActivePrincipal(anonymous))
                .thenThrow(new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required"));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.CONNECT, "", null, anonymous), channel))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        StompHeaderAccessor missingUser = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> missingUserMessage = MessageBuilder.createMessage(
                new byte[0], missingUser.getMessageHeaders());
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> interceptor.preSend(missingUserMessage, channel));
    }

    @Test
    void connectIgnoresClientSuppliedLoginAndRoleHeaders() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setUser(principal);
        accessor.setNativeHeader("login", "admin");
        accessor.setNativeHeader("passcode", "secret");
        accessor.setNativeHeader("role", "ADMIN");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(accounts).requireActivePrincipal(principal);
        verify(resources, never()).requireConversationAccess(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/messages",
            "/user/queue/notifications",
            "/user/queue/errors"
    })
    void subscribeAllowsOnlyTheThreeApprovedUserQueues(String destination) {
        assertThat(interceptor.preSend(
                message(StompCommand.SUBSCRIBE, "", destination), channel)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/conversations",
            "/user/queue/other",
            "/user/queue/messages/other",
            "/user/**",
            "/app/chat.send"
    })
    void subscribeRejectsTopicsWildcardsAndOtherQueues(String destination) {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.SUBSCRIBE, "", destination), channel));
    }

    @Test
    void sendRequiresAnApprovedDestinationAndConversationParticipant() {
        UUID conversationId = UUID.randomUUID();
        String payload = """
                {"conversationId":"%s","senderId":"%s","content":"hello"}
                """.formatted(conversationId, UUID.randomUUID());

        assertThat(interceptor.preSend(
                message(StompCommand.SEND, payload, "/app/chat.send"), channel)).isNotNull();
        verify(resources).requireConversationAccess(account, conversationId);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.SEND, payload, "/app/other.send"), channel));
    }

    @Test
    void sendUsesTrustedAccountAndIgnoresClientSenderId() {
        UUID conversationId = UUID.randomUUID();
        UUID spoofedSender = UUID.randomUUID();
        String payload = """
                {"conversationId":"%s","senderId":"%s","role":"ADMIN","content":"hello"}
                """.formatted(conversationId, spoofedSender);

        interceptor.preSend(message(StompCommand.SEND, payload, "/app/chat.send"), channel);

        verify(resources).requireConversationAccess(eq(account), eq(conversationId));
        verify(accounts).requireActivePrincipal(principal);
    }

    @Test
    void deniedConversationAccessIsPropagated() {
        UUID conversationId = UUID.randomUUID();
        String payload = "{\"conversationId\":\"" + conversationId + "\",\"content\":\"hello\"}";
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "Access is denied"))
                .when(resources).requireConversationAccess(account, conversationId);

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.SEND, payload, "/app/chat.send"), channel))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"conversationId\":\"not-a-uuid\"}",
            "{\"conversationId\":\"\"}",
            "not-json"
    })
    void sendRejectsMissingOrInvalidConversationId(String payload) {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.SEND, payload, "/app/chat.send"), channel));
        verify(resources, never()).requireConversationAccess(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deniedSubscriptionIsAuditedOnceWithTrustedActorAndDestination() {
        AuditEventService audit = mock(AuditEventService.class);
        ObjectProvider<AuditEventService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        StompAuthorizationInterceptor audited =
                new StompAuthorizationInterceptor(accounts, resources, provider);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> audited.preSend(
                        message(StompCommand.SUBSCRIBE, "", "/topic/conversations"), channel));

        verify(audit).recordWebSocketAuthFailure(userId.toString(), "/topic/conversations");
        verifyNoMoreInteractions(audit);
    }

    @Test
    void everyCommandRequiresATrustedAccount() {
        when(accounts.requireActivePrincipal(principal))
                .thenThrow(new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required"));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.SUBSCRIBE, "", "/user/queue/messages"), channel));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> interceptor.preSend(
                        message(StompCommand.DISCONNECT, ""), channel));
    }

    private Message<byte[]> message(StompCommand command, String payload) {
        return message(command, payload, null, principal);
    }

    private Message<byte[]> message(StompCommand command, String payload, String destination) {
        return message(command, payload, destination, principal);
    }

    private Message<byte[]> message(
            StompCommand command, String payload, String destination, Authentication user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setUser(user);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                accessor.getMessageHeaders());
    }
}
