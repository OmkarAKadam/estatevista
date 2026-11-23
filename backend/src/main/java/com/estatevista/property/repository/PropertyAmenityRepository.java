package com.estatevista.property.repository;

import java.util.List;
import java.util.UUID;

import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for property/amenity join rows.
 */
public interface PropertyAmenityRepository
        extends JpaRepository<PropertyAmenity, PropertyAmenityId> {

    /** Amenity links for a property, resolved inside the service transaction. */
    List<PropertyAmenity> findByProperty_Id(UUID propertyId);

    /**
     * Removes all links for a property before a replacement write. A bulk delete
     * avoids re-inserting the same composite key while removed entities would
     * still be managed by the persistence context.
     */
    @Modifying
    @Query("delete from PropertyAmenity pa where pa.property.id = :propertyId")
    void deleteByPropertyId(@Param("propertyId") UUID propertyId);
}
