package com.estatevista.websocket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.estatevista.authorization.PlatformRole;
import com.estatevista.config.WebSocketSecurityProperties;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-safe registry of pending handshakes and authenticated STOMP sockets.
 *
 * <p>Global transitions use {@code capacityGuard}; per-connection subscription
 * and outbound state uses the connection's read/write lock. The lock order is
 * always capacityGuard then connection write lock, never the reverse. A
 * revoked connection is marked CLOSED under that write lock before its
 * transport is closed, so an outbound dispatch either completes first or is
 * suppressed.</p>
 */
@Component
public final class WebSocketConnectionRegistry {

    public enum State {
        PENDING,
        AUTHENTICATED,
        CLOSED
    }

    public static final class ConnectionRecord {
        private final String connectionId;
        private final String springSessionId;
        private final UUID userId;
        private final Authentication principal;
        private final String clientIp;
        private final Instant createdAt;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final ConcurrentMap<String, String> subscriptions = new ConcurrentHashMap<>();
        private volatile State state = State.PENDING;
        private volatile String stompSessionId;
        private volatile PlatformRole role;
        private volatile WebSocketSession transport;
        private volatile Instant connectedAt;
        private volatile Instant lastActivityAt;

        private ConnectionRecord(
                String connectionId, String springSessionId, UUID userId, Authentication principal,
                String clientIp, PlatformRole role, Instant createdAt) {
            this.connectionId = connectionId;
            this.springSessionId = springSessionId;
            this.userId = userId;
            this.principal = principal;
            this.clientIp = clientIp;
            this.role = role;
            this.createdAt = createdAt;
            this.lastActivityAt = createdAt;
        }

        public String connectionId() {
            return connectionId;
        }

        public String springSessionId() {
            return springSessionId;
        }

        public UUID userId() {
            return userId;
        }

        public Authentication principal() {
            return principal;
        }

        public String clientIp() {
            return clientIp;
        }

        public State state() {
            return state;
        }

        public String stompSessionId() {
            return stompSessionId;
        }

        public PlatformRole role() {
            return role;
        }

        public WebSocketSession transport() {
            return transport;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant connectedAt() {
            return connectedAt;
        }

        public Instant lastActivityAt() {
            return lastActivityAt;
        }

        public List<String> subscriptions() {
            return List.copyOf(subscriptions.values());
        }

        void attachTransportUnderLock(WebSocketSession session) {
            this.transport = session;
        }

        void markClosedUnderLock() {
            state = State.CLOSED;
            subscriptions.clear();
        }

        public ReentrantReadWriteLock.ReadLock readLock() {
            return lock.readLock();
        }

        public ReentrantReadWriteLock.WriteLock writeLock() {
            return lock.writeLock();
        }
    }

    private final ConcurrentHashMap<String, ConnectionRecord> byConnectionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> byStompSessionId = new ConcurrentHashMap<>();
    private final Object capacityGuard = new Object();
    private final WebSocketSecurityProperties properties;

    public WebSocketConnectionRegistry(WebSocketSecurityProperties properties) {
        this.properties = properties;
    }

    /** Registers a trusted handshake as PENDING; rejects excess or duplicate state. */
    public ConnectionRecord registerPending(
            String springSessionId, UUID userId, Authentication principal,
            PlatformRole role, String clientIp) {
        synchronized (capacityGuard) {
            long active = byConnectionId.values().stream()
                    .filter(record -> userId.equals(record.userId) && record.state != State.CLOSED)
                    .count();
            if (active >= properties.getMaxSocketsPerUser()) {
                return null;
            }
            String connectionId = UUID.randomUUID().toString();
            ConnectionRecord record = new ConnectionRecord(
                    connectionId, springSessionId, userId, principal, clientIp, role, Instant.now());
            byConnectionId.put(connectionId, record);
            return record;
        }
    }

    /** Promotes one pending handshake to an authenticated STOMP connection. */
    public ConnectionRecord activate(String connectionId, String stompSessionId, PlatformRole role) {
        if (connectionId == null || stompSessionId == null) {
            return null;
        }
        synchronized (capacityGuard) {
            ConnectionRecord record = byConnectionId.get(connectionId);
            if (record == null || record.state != State.PENDING) {
                return null;
            }
            if (byStompSessionId.containsKey(stompSessionId)) {
                return null;
            }
            record.writeLock().lock();
            try {
                record.stompSessionId = stompSessionId;
                record.role = role;
                record.connectedAt = Instant.now();
                record.lastActivityAt = record.connectedAt;
                record.state = State.AUTHENTICATED;
            } finally {
                record.writeLock().unlock();
            }
            byStompSessionId.put(stompSessionId, record.connectionId);
            return record;
        }
    }

    /** Binds the real WebSocket transport supplied by the decorator. */
    public void attachTransport(String connectionId, WebSocketSession session) {
        if (connectionId == null || session == null) {
            return;
        }
        synchronized (capacityGuard) {
            ConnectionRecord record = byConnectionId.get(connectionId);
            if (record == null || record.state == State.CLOSED) {
                return;
            }
            record.writeLock().lock();
            try {
                record.attachTransportUnderLock(session);
            } finally {
                record.writeLock().unlock();
            }
        }
    }

    public ConnectionRecord byHandshake(String connectionId) {
        return connectionId == null ? null : byConnectionId.get(connectionId);
    }

    public ConnectionRecord byStompSession(String stompSessionId) {
        if (stompSessionId == null) {
            return null;
        }
        String connectionId = byStompSessionId.get(stompSessionId);
        return connectionId == null ? null : byConnectionId.get(connectionId);
    }

    public List<ConnectionRecord> forSpringSession(String springSessionId) {
        if (springSessionId == null) {
            return List.of();
        }
        return byConnectionId.values().stream()
                .filter(record -> springSessionId.equals(record.springSessionId)
                        && record.state != State.CLOSED)
                .toList();
    }

    public List<ConnectionRecord> forUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return byConnectionId.values().stream()
                .filter(record -> userId.equals(record.userId) && record.state != State.CLOSED)
                .toList();
    }

    public List<ConnectionRecord> all() {
        return new ArrayList<>(byConnectionId.values());
    }

    /** Returns false when the per-connection cap or duplicate subscription ID is hit. */
    public boolean addSubscription(
            ConnectionRecord record, String destination, String subscriptionId) {
        if (record == null || destination == null) {
            return false;
        }
        String key = subscriptionKey(destination, subscriptionId);
        record.writeLock().lock();
        try {
            if (record.state != State.AUTHENTICATED) {
                return false;
            }
            if (record.subscriptions.size() >= properties.getMaxSubscriptionsPerConnection()
                    && !record.subscriptions.containsKey(key)) {
                return false;
            }
            if (record.subscriptions.containsKey(key)) {
                return false;
            }
            record.subscriptions.put(key, destination);
            return true;
        } finally {
            record.writeLock().unlock();
        }
    }

    public void removeSubscription(ConnectionRecord record, String subscriptionId) {
        if (record == null || subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        record.writeLock().lock();
        try {
            record.subscriptions.remove(subscriptionId);
        } finally {
            record.writeLock().unlock();
        }
    }

    public void clearSubscriptions(ConnectionRecord record) {
        if (record == null) {
            return;
        }
        record.writeLock().lock();
        try {
            record.subscriptions.clear();
        } finally {
            record.writeLock().unlock();
        }
    }

    /**
     * Verifies that an outbound frame matches a live subscription on this exact
     * connection. Spring resolves {@code /user/queue/*} to
     * {@code /queue/*-<stompSessionId}> before outbound dispatch, so both the
     * requested and resolved forms are accepted for the same connection only.
     */
    public boolean hasSubscription(
            ConnectionRecord record, String destination, String subscriptionId) {
        if (record == null || destination == null) {
            return false;
        }
        record.readLock().lock();
        try {
            if (record.state != State.AUTHENTICATED) {
                return false;
            }
            String requested;
            if (subscriptionId != null && !subscriptionId.isBlank()) {
                requested = record.subscriptions.get(subscriptionId);
                if (requested == null) {
                    return false;
                }
            } else {
                requested = record.subscriptions.values().stream()
                        .filter(value -> matches(value, destination, record.stompSessionId))
                        .findFirst().orElse(null);
                if (requested == null) {
                    return false;
                }
            }
            return matches(requested, destination, record.stompSessionId);
        } finally {
            record.readLock().unlock();
        }
    }

    public void markActivity(ConnectionRecord record) {
        if (record != null) {
            record.lastActivityAt = Instant.now();
        }
    }

    /** Removes registry state without touching the transport. */
    public ConnectionRecord remove(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        synchronized (capacityGuard) {
            ConnectionRecord record = byConnectionId.remove(connectionId);
            if (record != null) {
                record.writeLock().lock();
                try {
                    record.markClosedUnderLock();
                    if (record.stompSessionId != null) {
                        byStompSessionId.remove(record.stompSessionId);
                    }
                } finally {
                    record.writeLock().unlock();
                }
            }
            return record;
        }
    }

    /** Removes state after the transport reports a normal or abnormal close. */
    public ConnectionRecord removeByTransport(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        Object handshakeId = session.getAttributes().get(WebSocketSessionAttributes.HANDSHAKE_ID);
        return remove(handshakeId instanceof String value ? value : null);
    }

    public int size() {
        return byConnectionId.size();
    }

    private static String subscriptionKey(String destination, String subscriptionId) {
        return subscriptionId != null && !subscriptionId.isBlank()
                ? subscriptionId : "destination:" + destination;
    }

    private static boolean matches(String requested, String delivered, String stompSessionId) {
        if (requested.equals(delivered)) {
            return true;
        }
        if (!requested.startsWith("/user/") || stompSessionId == null) {
            return false;
        }
        String resolved = "/" + requested.substring("/user/".length()) + "-" + stompSessionId;
        // Spring's user-destination resolver prefixes the session id with "user".
        // Accept both spellings, but only for this exact connection's session.
        String springResolved = "/" + requested.substring("/user/".length())
                + "-user" + stompSessionId;
        return resolved.equals(delivered) || springResolved.equals(delivered);
    }
}
