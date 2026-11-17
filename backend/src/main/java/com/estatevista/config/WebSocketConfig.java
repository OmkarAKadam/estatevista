package com.estatevista.config;

import java.util.List;

import com.estatevista.authorization.StompAuthorizationInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket/STOMP transport configuration only.
 *
 * <p>The approved architecture uses native WebSocket (no SockJS fallback) at
 * {@code /ws}, with direct browser-to-API connections and an exact Origin
 * allowlist.</p>
 *
 * <p><strong>Stage 3.5:</strong> the HTTP handshake requires the existing
 * server-side session, and a channel interceptor authorizes CONNECT, the three
 * approved user-queue SUBSCRIBE destinations, and SEND participant membership.
 * Message handlers, message persistence, and chat UI remain deferred to Stage 7.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;
    private final StompAuthorizationInterceptor stompAuthorizationInterceptor;

    public WebSocketConfig(
            AppProperties properties,
            StompAuthorizationInterceptor stompAuthorizationInterceptor) {
        this.properties = properties;
        this.stompAuthorizationInterceptor = stompAuthorizationInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String endpoint = properties.websocket().endpoint();
        List<String> origins = AllowedOrigins.requireExplicit(
                properties.websocket().allowedOrigins(),
                "app.websocket.allowed-origins");

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "app.websocket.endpoint must be a non-empty path");
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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthorizationInterceptor);
    }
}
