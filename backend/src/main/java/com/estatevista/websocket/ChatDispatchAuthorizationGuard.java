package com.estatevista.websocket;

import com.estatevista.websocket.OutboundDispatchAuthorizationService.Boundary;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Decision;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Frame;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Chat-specific recheck when a frame enters {@code clientOutboundChannel}.
 *
 * <p>The Stage 7.2 outbound interceptor runs first. This guard applies the same
 * shared authorization decisions as the final transport boundary, but at the
 * channel entry point. Frames that survive both checks are still rechecked by
 * the authorized transport session after executor queueing.</p>
 */
@Component
public final class ChatDispatchAuthorizationGuard implements ChannelInterceptor {

    private final WebSocketConnectionRegistry registry;
    private final OutboundDispatchAuthorizationService authorization;
    private final WebSocketSessionRevocationService revocation;

    public ChatDispatchAuthorizationGuard(
            WebSocketConnectionRegistry registry,
            OutboundDispatchAuthorizationService authorization,
            WebSocketSessionRevocationService revocation) {
        this.registry = registry;
        this.authorization = authorization;
        this.revocation = revocation;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String stompSessionId = accessor.getSessionId();
        WebSocketConnectionRegistry.ConnectionRecord record =
                stompSessionId == null ? null : registry.byStompSession(stompSessionId);
        Frame frame = new Frame(
                accessor.getDestination(),
                accessor.getSubscriptionId(),
                authorization.payloadText(message.getPayload()));

        Decision decision = authorization.authorize(Boundary.CHANNEL, record, frame);
        if (decision.outcome() == OutboundDispatchAuthorizationService.Outcome.REVOKE) {
            revocation.revokeConnection(record, decision.revocationReason());
            return null;
        }
        return decision.outcome() == OutboundDispatchAuthorizationService.Outcome.ALLOW
                ? message : null;
    }
}
