package com.estatevista.property.repository;

import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for the property-amenity join rows.
 */
public interface PropertyAmenityRepository
        extends JpaRepository<PropertyAmenity, PropertyAmenityId> {
}
