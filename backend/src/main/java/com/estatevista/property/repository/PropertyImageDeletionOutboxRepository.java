package com.estatevista.property.repository;

import java.util.List;
import java.util.UUID;

import com.estatevista.property.entity.PropertyImageDeletionOutbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Durable provider-cleanup tasks. Claim uses PostgreSQL row locking with
 * {@code SKIP LOCKED} so multiple workers never process the same task.
 */
public interface PropertyImageDeletionOutboxRepository
        extends JpaRepository<PropertyImageDeletionOutbox, UUID> {

    @Query(value = """
            SELECT id FROM property_image_deletion_outbox
            WHERE (status IN ('PENDING', 'FAILED') AND next_attempt_at <= now())
               OR (status = 'PROCESSING' AND locked_until < now())
            ORDER BY next_attempt_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("limit") int limit);

    long countByStatus(String status);
}
