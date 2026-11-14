package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.Amenity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for reusable amenity definitions.
 */
public interface AmenityRepository extends JpaRepository<Amenity, UUID> {
}
