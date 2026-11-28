package com.estatevista.websocket;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.estatevista.websocket.OutboundDispatchAuthorizationService.Boundary;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Decision;
import com.estatevista.websocket.OutboundDispatchAuthorizationService.Frame;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Binds the real transport to the authoritative registry and wraps every
 * session with the final authorization check.
 *
 * <p>Spring's {@code SubProtocolWebSocketHandler} applies its
 * {@code ConcurrentWebSocketSessionDecorator} around this wrapper, so the
 * recheck runs after outbound-channel executor queueing but before the real
 * transport write. The connection read lock is held through that write;
 * revocation takes the write lock, so it either completes first or waits for
 * the in-flight frame to finish.</p>
 */
@Component
public final class WebSocketTransportLifecycle implements WebSocketHandlerDecoratorFactory {

    private static final Pattern BODY_SEPARATOR = Pattern.compile("\\r?\\n\\r?\\n");

    private final WebSocketConnectionRegistry registry;
    private final OutboundDispatchAuthorizationService authorization;
    private final WebSocketSessionRevocationService revocation;

    public WebSocketTransportLifecycle(
            WebSocketConnectionRegistry registry,
            OutboundDispatchAuthorizationService authorization,
            WebSocketSessionRevocationService revocation) {
        this.registry = registry;
        this.authorization = authorization;
        this.revocation = revocation;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                Object handshakeId = session.getAttributes()
                        .get(WebSocketSessionAttributes.HANDSHAKE_ID);
                WebSocketConnectionRegistry.ConnectionRecord record =
                        handshakeId instanceof String value ? registry.byHandshake(value) : null;
                AuthorizedWebSocketSession authorized = new AuthorizedWebSocketSession(
                        session, record, authorization, revocation);
                if (handshakeId instanceof String value) {
                    registry.attachTransport(value, authorized);
                }
                super.afterConnectionEstablished(authorized);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    registry.removeByTransport(session);
                }
            }
        };
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        WebSocketConnectionRegistry.ConnectionRecord record =
                registry.byStompSession(event.getSessionId());
        if (record != null) {
            registry.remove(record.connectionId());
        }
    }

    /** Final server-controlled write boundary for one real WebSocket session. */
    private static final class AuthorizedWebSocketSession extends WebSocketSessionDecorator {

        private final WebSocketConnectionRegistry.ConnectionRecord record;
        private final OutboundDispatchAuthorizationService authorization;
        private final WebSocketSessionRevocationService revocation;

        private AuthorizedWebSocketSession(
                WebSocketSession delegate,
                WebSocketConnectionRegistry.ConnectionRecord record,
                OutboundDispatchAuthorizationService authorization,
                WebSocketSessionRevocationService revocation) {
            super(delegate);
            this.record = record;
            this.authorization = authorization;
            this.revocation = revocation;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            StompMessageFrame frame = parseStompMessage(message);
            if (frame == null) {
                getDelegate().sendMessage(message);
                return;
            }
            Frame outbound = new Frame(frame.destination(), frame.subscriptionId(), frame.body());
            if (record == null) {
                Decision decision = authorization.authorize(Boundary.TRANSPORT, null, outbound);
                if (decision.outcome() == OutboundDispatchAuthorizationService.Outcome.ALLOW) {
                    getDelegate().sendMessage(message);
                }
                return;
            }

            Decision decision;
            record.readLock().lock();
            try {
                decision = authorization.authorizeLocked(Boundary.TRANSPORT, record, outbound);
                if (decision.outcome() == OutboundDispatchAuthorizationService.Outcome.ALLOW) {
                    getDelegate().sendMessage(message);
                }
            } finally {
                record.readLock().unlock();
            }
            if (decision.outcome() == OutboundDispatchAuthorizationService.Outcome.REVOKE) {
                // Never take the write lock while holding the read lock.
                revocation.revokeConnection(record, decision.revocationReason());
            }
        }
    }

    /** Parsed STOMP MESSAGE headers and body; non-MESSAGE frames are left alone. */
    private record StompMessageFrame(String destination, String subscriptionId, String body) {
    }

    private static StompMessageFrame parseStompMessage(WebSocketMessage<?> message) {
        if (!(message instanceof TextMessage text)) {
            return null;
        }
        String payload = text.getPayload();
        Matcher separator = BODY_SEPARATOR.matcher(payload);
        if (!separator.find()) {
            return null;
        }
        String[] lines = payload.substring(0, separator.start()).split("\\r?\\n");
        if (lines.length == 0 || !"MESSAGE".equals(lines[0].trim())) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0) {
                headers.put(
                        lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[index].substring(colon + 1).trim());
            }
        }
        String destination = headers.get("destination");
        if (destination == null) {
            return null;
        }
        String body = payload.substring(separator.end());
        int nul = body.indexOf('\0');
        if (nul >= 0) {
            body = body.substring(0, nul);
        }
        return new StompMessageFrame(destination, headers.get("subscription"), body);
    }
}
