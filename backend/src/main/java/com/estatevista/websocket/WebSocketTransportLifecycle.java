package com.estatevista.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Binds the real transport to the authoritative registry and removes state on
 * transport close. The handshake interceptor cannot see a WebSocketSession, so
 * the transport decorator is the only place where the actual close handle is
 * available.
 */
@Component
public final class WebSocketTransportLifecycle implements WebSocketHandlerDecoratorFactory {

    private final WebSocketConnectionRegistry registry;

    public WebSocketTransportLifecycle(WebSocketConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                Object handshakeId = session.getAttributes()
                        .get(WebSocketSessionAttributes.HANDSHAKE_ID);
                if (handshakeId instanceof String value) {
                    registry.attachTransport(value, session);
                }
                super.afterConnectionEstablished(session);
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
}
