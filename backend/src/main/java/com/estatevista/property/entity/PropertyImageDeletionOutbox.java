package com.estatevista.property.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Durable cleanup task for an image whose active metadata has already been
 * removed in the same committed transaction (approved deletion outbox ADR).
 */
@Entity
@Table(name = "property_image_deletion_outbox")
public class PropertyImageDeletionOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    @Id
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "image_id", nullable = false)
    private UUID imageId;

    @Column(name = "storage_provider", nullable = false, length = 30)
    private String storageProvider;

    @Column(name = "storage_public_id", length = 255)
    private String storagePublicId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PropertyImageDeletionOutbox() {
    }

    public PropertyImageDeletionOutbox(
            UUID id, UUID propertyId, UUID imageId, String storageProvider,
            String storagePublicId, UUID actorId, Instant now) {
        this.id = id;
        this.propertyId = propertyId;
        this.imageId = imageId;
        this.storageProvider = storageProvider;
        this.storagePublicId = storagePublicId;
        this.actorId = actorId;
        this.status = STATUS_PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public UUID getImageId() {
        return imageId;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public String getStoragePublicId() {
        return storagePublicId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isFinished() {
        return STATUS_COMPLETED.equals(status) || STATUS_DEAD_LETTER.equals(status);
    }

    public void markProcessing(Instant now, Instant leaseUntil) {
        this.status = STATUS_PROCESSING;
        this.attempts += 1;
        this.lastAttemptAt = now;
        this.lockedUntil = leaseUntil;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        this.status = STATUS_COMPLETED;
        this.lockedUntil = null;
        this.lastError = null;
        this.nextAttemptAt = now;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(Instant now, Instant retryAt, String reason) {
        this.status = STATUS_FAILED;
        this.lockedUntil = null;
        this.lastError = reason;
        this.nextAttemptAt = retryAt;
        this.updatedAt = now;
    }

    public void markDeadLetter(Instant now, String reason) {
        this.status = STATUS_DEAD_LETTER;
        this.lockedUntil = null;
        this.lastError = reason;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }
}
