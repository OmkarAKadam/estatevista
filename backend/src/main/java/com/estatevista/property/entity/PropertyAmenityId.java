package com.estatevista.property.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite identifier for the property-amenity join table
 * ({@code property_amenities}, DATABASE_DESIGN.md §8.2).
 */
@Embeddable
public class PropertyAmenityId implements Serializable {

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "amenity_id", nullable = false)
    private UUID amenityId;

    public PropertyAmenityId() {
    }

    public PropertyAmenityId(UUID propertyId, UUID amenityId) {
        this.propertyId = propertyId;
        this.amenityId = amenityId;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public void setPropertyId(UUID propertyId) {
        this.propertyId = propertyId;
    }

    public UUID getAmenityId() {
        return amenityId;
    }

    public void setAmenityId(UUID amenityId) {
        this.amenityId = amenityId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyAmenityId that)) {
            return false;
        }
        return Objects.equals(propertyId, that.propertyId)
                && Objects.equals(amenityId, that.amenityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyId, amenityId);
    }
}
