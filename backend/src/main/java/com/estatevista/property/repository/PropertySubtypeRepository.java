package com.estatevista.property.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.property.entity.PropertySubtype;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property subtypes.
 */
public interface PropertySubtypeRepository extends JpaRepository<PropertySubtype, UUID> {

    /** Rejects a subtype that does not belong to the selected category. */
    Optional<PropertySubtype> findByIdAndCategory_Id(UUID id, UUID categoryId);

    /** Stable, deterministic ordering for the public subtypes lookup. */
    List<PropertySubtype> findByCategory_Id(UUID categoryId, Sort sort);
}
