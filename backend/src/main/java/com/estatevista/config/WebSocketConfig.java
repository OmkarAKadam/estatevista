package com.estatevista.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket/STOMP transport configuration only.
 *
 * <p>The approved architecture uses native WebSocket (no SockJS fallback) at
 * {@code /ws}, with direct browser-to-API connections and an exact Origin
 * allowlist. No message handlers, destinations, or subscription authorization
 * are implemented in this stage.</p>
 *
 * <p><strong>Pending:</strong> session-based handshake authentication and
 * STOMP CONNECT/SUBSCRIBE/SEND authorization are part of the authentication
 * and messaging stages. Until then the handshake must not be exposed publicly.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;

    public WebSocketConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String endpoint = properties.websocket().endpoint();
        List<String> origins = properties.websocket().allowedOrigins();

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "app.websocket.endpoint must be a non-empty path");
        }
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException(
                    "app.websocket.allowed-origins must contain at least one explicit origin");
        }
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "Wildcard WebSocket origins are not permitted");
        }

        registry.addEndpoint(endpoint)
                .setAllowedOrigins(origins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
