package com.estatevista.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * Stage 7.2 WebSocket security limits and lifecycle timings.
 *
 * <p>All values are externalised under {@code app.websocket-security}. The
 * revalidation interval is bounded: a value above {@link #MAX_REVALIDATION_INTERVAL_MS}
 * fails startup rather than silently weakening the F-3 detection bound.</p>
 */
@ConfigurationProperties(prefix = "app.websocket-security")
public class WebSocketSecurityProperties {

    public static final int MAX_REVALIDATION_INTERVAL_MS = 60_000;
    public static final int DEFAULT_REVALIDATION_INTERVAL_MS = 10_000;

    private int maxSocketsPerUser = 5;
    private int maxSubscriptionsPerConnection = 3;
    private int maxFrameBytes = 32 * 1024;
    private int revalidationIntervalMs = DEFAULT_REVALIDATION_INTERVAL_MS;
    private int pendingHandshakeTimeoutMs = 30_000;
    private int staleConnectionTimeoutMs = 30_000;
    private int validationCacheTtlMs = 1_000;
    private int validationCacheMaxEntries = 1_024;
    private long serverHeartbeatMs = 10_000L;
    private long clientHeartbeatMs = 10_000L;

    @PostConstruct
    public void validate() {
        requirePositive(maxSocketsPerUser, "max-sockets-per-user");
        requirePositive(maxSubscriptionsPerConnection, "max-subscriptions-per-connection");
        requirePositive(maxFrameBytes, "max-frame-bytes");
        if (revalidationIntervalMs < 1_000 || revalidationIntervalMs > MAX_REVALIDATION_INTERVAL_MS) {
            throw new IllegalStateException(
                    "app.websocket-security.revalidation-interval-ms must be between 1000 and "
                            + MAX_REVALIDATION_INTERVAL_MS);
        }
        requirePositive(pendingHandshakeTimeoutMs, "pending-handshake-timeout-ms");
        requirePositive(staleConnectionTimeoutMs, "stale-connection-timeout-ms");
        if (validationCacheTtlMs < 0 || validationCacheTtlMs > 5_000) {
            throw new IllegalStateException(
                    "app.websocket-security.validation-cache-ttl-ms must be between 0 and 5000");
        }
        requirePositive(validationCacheMaxEntries, "validation-cache-max-entries");
        requirePositive(serverHeartbeatMs, "server-heartbeat-ms");
        requirePositive(clientHeartbeatMs, "client-heartbeat-ms");
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalStateException("app.websocket-security." + name + " must be positive");
        }
    }

    public int getMaxSocketsPerUser() {
        return maxSocketsPerUser;
    }

    public void setMaxSocketsPerUser(int maxSocketsPerUser) {
        this.maxSocketsPerUser = maxSocketsPerUser;
    }

    public int getMaxSubscriptionsPerConnection() {
        return maxSubscriptionsPerConnection;
    }

    public void setMaxSubscriptionsPerConnection(int maxSubscriptionsPerConnection) {
        this.maxSubscriptionsPerConnection = maxSubscriptionsPerConnection;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public int getRevalidationIntervalMs() {
        return revalidationIntervalMs;
    }

    public void setRevalidationIntervalMs(int revalidationIntervalMs) {
        this.revalidationIntervalMs = revalidationIntervalMs;
    }

    public int getPendingHandshakeTimeoutMs() {
        return pendingHandshakeTimeoutMs;
    }

    public void setPendingHandshakeTimeoutMs(int pendingHandshakeTimeoutMs) {
        this.pendingHandshakeTimeoutMs = pendingHandshakeTimeoutMs;
    }

    public int getStaleConnectionTimeoutMs() {
        return staleConnectionTimeoutMs;
    }

    public void setStaleConnectionTimeoutMs(int staleConnectionTimeoutMs) {
        this.staleConnectionTimeoutMs = staleConnectionTimeoutMs;
    }

    public int getValidationCacheTtlMs() {
        return validationCacheTtlMs;
    }

    public void setValidationCacheTtlMs(int validationCacheTtlMs) {
        this.validationCacheTtlMs = validationCacheTtlMs;
    }

    public int getValidationCacheMaxEntries() {
        return validationCacheMaxEntries;
    }

    public void setValidationCacheMaxEntries(int validationCacheMaxEntries) {
        this.validationCacheMaxEntries = validationCacheMaxEntries;
    }

    public long getServerHeartbeatMs() {
        return serverHeartbeatMs;
    }

    public void setServerHeartbeatMs(long serverHeartbeatMs) {
        this.serverHeartbeatMs = serverHeartbeatMs;
    }

    public long getClientHeartbeatMs() {
        return clientHeartbeatMs;
    }

    public void setClientHeartbeatMs(long clientHeartbeatMs) {
        this.clientHeartbeatMs = clientHeartbeatMs;
    }
}
