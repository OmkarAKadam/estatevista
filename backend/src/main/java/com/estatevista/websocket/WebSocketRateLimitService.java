package com.estatevista.websocket;

import java.util.UUID;

import com.estatevista.security.RateLimitService;

import org.springframework.stereotype.Service;

/**
 * Reusable inbound message-rate scopes. Stage 7.4 applies the same scopes to
 * persisted message handlers; Stage 7.2 enforces them at the protected STOMP
 * SEND boundary and verifies isolation with controlled fixtures.
 */
@Service
public final class WebSocketRateLimitService {

    private final RateLimitService rateLimitService;

    public WebSocketRateLimitService(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    public void acquireUser(UUID userId) {
        rateLimitService.acquire(
                RateLimitService.SCOPE_WS_MESSAGE_USER, "user:" + (userId == null ? "unknown" : userId));
    }

    public void acquireConversation(UUID userId, UUID conversationId) {
        rateLimitService.acquire(
                RateLimitService.SCOPE_WS_MESSAGE_CONVERSATION,
                "user:" + (userId == null ? "unknown" : userId)
                        + "|conversation:" + (conversationId == null ? "unknown" : conversationId));
    }
}
