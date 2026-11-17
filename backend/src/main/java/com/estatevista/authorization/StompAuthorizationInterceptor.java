package com.estatevista.authorization;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * STOMP authorization foundation (Stage 3.5).
 *
 * <p>Only an authenticated server-side session may connect. Subscriptions are
 * limited to the three approved per-user queues; conversation topics, wildcard
 * destinations, and another user's queue are rejected. SEND is limited to the
 * approved {@code /app/chat.send} destination and requires that the trusted
 * principal be a participant of the conversation named in the payload.</p>
 *
 * <p>This is an authorization boundary only. It does not implement chat
 * handlers, message persistence, or a frontend chat UI; those belong to Stage 7.
 * Client supplied sender IDs, logins, and roles are never read.</p>
 */
@Component
public final class StompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages",
            "/user/queue/notifications",
            "/user/queue/errors");
    private static final String SEND_DESTINATION = "/app/chat.send";
    private static final Pattern CONVERSATION_ID =
            Pattern.compile("\"conversationId\"\\s*:\\s*\"([^\"]+)\"");

    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;

    public StompAuthorizationInterceptor(
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources) {
        this.accounts = accounts;
        this.resources = resources;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            throw denied();
        }

        Principal principal = accessor.getUser();
        AuthenticatedAccount account = accounts.requireActivePrincipal(principal);

        switch (command) {
            case CONNECT, STOMP -> {
                // The principal came from the authenticated HTTP handshake. STOMP
                // login/passcode headers are intentionally ignored.
                if (!(principal instanceof Authentication authentication)
                        || !authentication.isAuthenticated()) {
                    throw denied();
                }
            }
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination == null || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                    throw denied();
                }
            }
            case SEND -> {
                if (!SEND_DESTINATION.equals(accessor.getDestination())) {
                    throw denied();
                }
                UUID conversationId = conversationIdOf(message.getPayload());
                resources.requireConversationAccess(account, conversationId);
            }
            default -> {
                // DISCONNECT and framework frames remain permitted for an
                // already authenticated account.
            }
        }
        return message;
    }

    private static UUID conversationIdOf(Object payload) {
        String text = switch (payload) {
            case String value -> value;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> null;
        };
        if (text == null) {
            throw denied();
        }
        Matcher matcher = CONVERSATION_ID.matcher(text);
        if (!matcher.find()) {
            throw denied();
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw denied();
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Messaging access is denied");
    }
}
