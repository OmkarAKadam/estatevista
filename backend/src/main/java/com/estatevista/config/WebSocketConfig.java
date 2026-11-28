package com.estatevista.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import com.estatevista.authorization.StompAuthorizationInterceptor;
import com.estatevista.websocket.ChatDispatchAuthorizationGuard;
import com.estatevista.websocket.WebSocketHandshakeInterceptor;
import com.estatevista.websocket.WebSocketOutboundAuthorizationInterceptor;
import com.estatevista.websocket.WebSocketSecurityInterceptor;
import com.estatevista.websocket.WebSocketTransportLifecycle;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Native WebSocket/STOMP transport and security wiring.
 *
 * <p>The approved architecture uses native WebSocket (no SockJS fallback) at
 * {@code /ws}, with direct browser-to-API connections and an exact Origin
 * allowlist. Stage 7.2 adds the trusted handshake boundary, the protected
 * inbound CONNECT/SUBSCRIBE/SEND boundary, the final outbound authorization
 * boundary, authoritative session lifecycle checks and bounded limits.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;
    private final WebSocketSecurityProperties securityProperties;
    private final WebSocketHandshakeInterceptor handshakeInterceptor;
    private final WebSocketSecurityInterceptor securityInterceptor;
    private final StompAuthorizationInterceptor stompAuthorizationInterceptor;
    private final WebSocketOutboundAuthorizationInterceptor outboundAuthorizationInterceptor;
    private final ChatDispatchAuthorizationGuard chatDispatchAuthorizationGuard;
    private final WebSocketTransportLifecycle transportLifecycle;
    private final TaskScheduler taskScheduler;

    public WebSocketConfig(
            AppProperties properties,
            WebSocketSecurityProperties securityProperties,
            WebSocketHandshakeInterceptor handshakeInterceptor,
            WebSocketSecurityInterceptor securityInterceptor,
            StompAuthorizationInterceptor stompAuthorizationInterceptor,
            WebSocketOutboundAuthorizationInterceptor outboundAuthorizationInterceptor,
            ChatDispatchAuthorizationGuard chatDispatchAuthorizationGuard,
            WebSocketTransportLifecycle transportLifecycle,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.handshakeInterceptor = handshakeInterceptor;
        this.securityInterceptor = securityInterceptor;
        this.stompAuthorizationInterceptor = stompAuthorizationInterceptor;
        this.outboundAuthorizationInterceptor = outboundAuthorizationInterceptor;
        this.chatDispatchAuthorizationGuard = chatDispatchAuthorizationGuard;
        this.transportLifecycle = transportLifecycle;
        this.taskScheduler = taskScheduler;
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
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(origins.toArray(String[]::new));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(securityProperties.getMaxFrameBytes())
                .setSendTimeLimit(securityProperties.getStaleConnectionTimeoutMs())
                .setSendBufferSizeLimit(securityProperties.getMaxFrameBytes() * 4)
                .setTimeToFirstMessage(securityProperties.getPendingHandshakeTimeoutMs())
                .addDecoratorFactory(transportLifecycle);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic")
                .setTaskScheduler(taskScheduler)
                .setHeartbeatValue(new long[] {
                        securityProperties.getServerHeartbeatMs(),
                        securityProperties.getClientHeartbeatMs()});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // The security interceptor runs first: it authenticates/activates the
        // STOMP session and installs the trusted principal before destination
        // and conversation authorization.
        registration.interceptors(securityInterceptor, stompAuthorizationInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(outboundAuthorizationInterceptor, chatDispatchAuthorizationGuard);
    }
}
