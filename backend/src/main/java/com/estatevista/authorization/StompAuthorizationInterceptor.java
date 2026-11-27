package com.estatevista.authorization;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Client supplied sender IDs, logins, and roles are never read. Denials are
 * recorded as redacted {@code SECURITY_WS_AUTH_FAILURE} audit events.</p>
 */
@Component
public final class StompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthorizationInterceptor.class);

    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages",
            "/user/queue/notifications",
            "/user/queue/errors");
    private static final String SEND_DESTINATION = "/app/chat.send";
    private static final Pattern CONVERSATION_ID =
            Pattern.compile("\"conversationId\"\\s*:\\s*\"([^\"]+)\"");

    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;
    private final ObjectProvider<AuditEventService> auditEventService;

    /** Constructor for isolated authorization tests without an audit bean. */
    public StompAuthorizationInterceptor(
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources) {
        this(accounts, resources, null);
    }

    @Autowired
    public StompAuthorizationInterceptor(
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources,
            ObjectProvider<AuditEventService> auditEventService) {
        this.accounts = accounts;
        this.resources = resources;
        this.auditEventService = auditEventService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        try {
            return authorize(message, accessor);
        } catch (AccessDeniedException | ApiException exception) {
            auditDenial(accessor);
            throw exception;
        }
    }

    private Message<?> authorize(Message<?> message, StompHeaderAccessor accessor) {
        StompCommand command = accessor.getCommand();
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw denied();
        }
        AuthenticatedAccount account = accounts.requireActivePrincipal(principal);
        if (command == null) {
            // Native STOMP heartbeat: the trusted principal must already exist,
            // but there is no destination to authorize.
            return message;
        }

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

    private void auditDenial(StompHeaderAccessor accessor) {
        if (auditEventService == null) {
            return;
        }
        try {
            AuditEventService audit = auditEventService.getIfAvailable();
            if (audit != null) {
                audit.recordWebSocketAuthFailure(
                        principalName(accessor.getUser()), safeDestination(accessor.getDestination()));
            }
        } catch (RuntimeException exception) {
            // Auditing is best-effort and must not alter the STOMP denial.
            log.warn("STOMP audit write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private static String principalName(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return null;
        }
        return principal.getName();
    }

    private static String safeDestination(String destination) {
        return destination != null && destination.matches("/[A-Za-z0-9/_-]{1,80}")
                ? destination
                : "unknown";
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
