package com.estatevista.property.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for property listings.
 */
public interface PropertyRepository
        extends JpaRepository<Property, UUID>, JpaSpecificationExecutor<Property> {

    /** Single-query visibility check for public detail reads. */
    Optional<Property> findByIdAndStatus(UUID id, PropertyStatus status);

    /** Supports bounded, server-generated slug collision retries. */
    boolean existsBySlug(String slug);

    /** Serializes image-count and primary-image mutations for one property. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Property p where p.id = :id")
    Optional<Property> findByIdForUpdate(@Param("id") UUID id);
}
