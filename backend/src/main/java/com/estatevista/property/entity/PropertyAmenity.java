package com.estatevista.property.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Join row linking a property and an amenity ({@code property_amenities},
 * DATABASE_DESIGN.md §8.2).
 *
 * <p>The composite primary key prevents duplicate amenities on one property.
 * The database cascades removal from {@code properties} but uses
 * {@code ON DELETE RESTRICT} from {@code amenities} (V7), so an amenity with
 * existing links cannot be deleted without first removing those links.</p>
 */
@Entity
@Table(name = "property_amenities")
public class PropertyAmenity {

    @EmbeddedId
    private PropertyAmenityId id;

    @MapsId("propertyId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @MapsId("amenityId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amenity_id", nullable = false)
    private Amenity amenity;

    public PropertyAmenity() {
    }

    public PropertyAmenityId getId() {
        return id;
    }

    public void setId(PropertyAmenityId id) {
        this.id = id;
    }

    public Property getProperty() {
        return property;
    }

    public void setProperty(Property property) {
        this.property = property;
    }

    public Amenity getAmenity() {
        return amenity;
    }

    public void setAmenity(Amenity amenity) {
        this.amenity = amenity;
    }
}
