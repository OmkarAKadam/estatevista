package com.estatevista.property.repository;

import java.util.List;
import java.util.UUID;

import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property/amenity join rows.
 */
public interface PropertyAmenityRepository
        extends JpaRepository<PropertyAmenity, PropertyAmenityId> {

    /** Amenity links for a property, resolved inside the service transaction. */
    List<PropertyAmenity> findByProperty_Id(UUID propertyId);
}
