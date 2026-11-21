package com.estatevista.property.repository;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence access for property listings.
 */
public interface PropertyRepository
        extends JpaRepository<Property, UUID>, JpaSpecificationExecutor<Property> {

    /** Single-query visibility check for public detail reads. */
    Optional<Property> findByIdAndStatus(UUID id, PropertyStatus status);

    /** Supports bounded, server-generated slug collision retries. */
    boolean existsBySlug(String slug);
}
