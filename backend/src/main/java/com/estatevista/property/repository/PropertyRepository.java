package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.Property;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property listings.
 */
public interface PropertyRepository extends JpaRepository<Property, UUID> {
}
