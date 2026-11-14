package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.PropertySubtype;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property subtypes.
 */
public interface PropertySubtypeRepository extends JpaRepository<PropertySubtype, UUID> {
}
