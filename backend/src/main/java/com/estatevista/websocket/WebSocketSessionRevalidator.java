package com.estatevista.websocket;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded authoritative revalidation of tracked sockets.
 *
 * <p>The scheduled tick is deliberately simple and deterministic: the public
 * {@link #revalidateAll()} method is called directly by tests so lifecycle
 * evidence does not depend on wall-clock timing. The configured interval is
 * bounded by {@link com.estatevista.config.WebSocketSecurityProperties}.</p>
 */
@Service
public final class WebSocketSessionRevalidator {

    private final WebSocketConnectionRegistry registry;
    private final AuthoritativeSessionValidator validator;
    private final WebSocketSessionRevocationService revocation;
    private final com.estatevista.config.WebSocketSecurityProperties properties;

    public WebSocketSessionRevalidator(
            WebSocketConnectionRegistry registry,
            AuthoritativeSessionValidator validator,
            WebSocketSessionRevocationService revocation,
            com.estatevista.config.WebSocketSecurityProperties properties) {
        this.registry = registry;
        this.validator = validator;
        this.revocation = revocation;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.websocket-security.revalidation-interval-ms:10000}",
            initialDelayString = "${app.websocket-security.revalidation-interval-ms:10000}")
    public void scheduledRevalidation() {
        revalidateAll();
    }

    /** Rechecks every live record once and closes those that fail. */
    public int revalidateAll() {
        List<WebSocketConnectionRegistry.ConnectionRecord> records = registry.all();
        int closed = 0;
        Instant now = Instant.now();
        for (WebSocketConnectionRegistry.ConnectionRecord record : records) {
            if (record.state() == WebSocketConnectionRegistry.State.CLOSED) {
                continue;
            }
            Duration age = Duration.between(
                    record.state() == WebSocketConnectionRegistry.State.PENDING
                            ? record.createdAt() : record.lastActivityAt(),
                    now);
            long timeout = record.state() == WebSocketConnectionRegistry.State.PENDING
                    ? properties.getPendingHandshakeTimeoutMs()
                    : properties.getStaleConnectionTimeoutMs();
            if (age.toMillis() > timeout) {
                if (revocation.revokeConnection(record, REASON_STALE)) {
                    closed++;
                }
                continue;
            }
            if (record.state() != WebSocketConnectionRegistry.State.AUTHENTICATED) {
                continue;
            }
            AuthoritativeSessionValidator.ValidationResult result = validator.validate(
                    record.springSessionId(), record.userId().toString(),
                    record.springSessionId(), true);
            if (!result.valid()) {
                String reason = switch (result.reason()) {
                    case EXPIRED_SESSION -> WebSocketSessionRevocationService.REASON_SESSION_EXPIRED;
                    case VALIDATION_FAILURE ->
                            WebSocketSessionRevocationService.REASON_VALIDATION_FAILURE;
                    default -> WebSocketSessionRevocationService.REASON_AUTHORIZATION_LOSS;
                };
                if (revocation.revokeConnection(record, reason)) {
                    closed++;
                }
            }
        }
        return closed;
    }

    private static final String REASON_STALE =
            WebSocketSessionRevocationService.REASON_VALIDATION_FAILURE;
}
