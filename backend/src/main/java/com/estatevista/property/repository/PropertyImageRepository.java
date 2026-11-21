package com.estatevista.property.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.estatevista.property.entity.PropertyImage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property image references.
 */
public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {

    /** Ordered metadata for manager detail views. */
    List<PropertyImage> findByProperty_IdOrderBySortOrderAsc(UUID propertyId);

    /** Batched primary-image lookup for list pages (avoids per-row N+1 reads). */
    List<PropertyImage> findByProperty_IdInAndIsPrimaryTrue(Collection<UUID> propertyIds);
}
