package com.estatevista.property.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational controls for the approved provider-deletion outbox. */
@ConfigurationProperties(prefix = "app.image-cleanup")
public class ImageCleanupProperties {

    private boolean enabled = true;
    private long pollMs = 5000;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private long backoffMs = 1000;
    private long maxBackoffMs = 60_000;
    private int leaseSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollMs() {
        return pollMs;
    }

    public void setPollMs(long pollMs) {
        this.pollMs = pollMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }
}
