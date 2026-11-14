package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.PropertyCategory;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property categories.
 */
public interface PropertyCategoryRepository extends JpaRepository<PropertyCategory, UUID> {
}
