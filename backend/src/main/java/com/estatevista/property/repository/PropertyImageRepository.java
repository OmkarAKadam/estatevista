package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.PropertyImage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property image references.
 */
public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {
}
