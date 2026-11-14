package com.estatevista.property.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Residential-specific property attributes ({@code property_residential_details},
 * DATABASE_DESIGN.md §6.4).
 *
 * <p>The shared primary key is the owning property identifier. Which detail
 * table applies to a category is a service-layer rule (§6.5).</p>
 */
@Entity
@Table(name = "property_residential_details")
public class PropertyResidentialDetails {

    @Id
    @Column(name = "property_id")
    private UUID propertyId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "bedrooms")
    private Short bedrooms;

    @Column(name = "bathrooms")
    private Short bathrooms;

    @Column(name = "balconies")
    private Short balconies;

    @Column(name = "furnishing_status", length = 30)
    private String furnishingStatus;

    @Column(name = "floor_number")
    private Short floorNumber;

    @Column(name = "total_floors")
    private Short totalFloors;

    @Column(name = "parking_spaces")
    private Short parkingSpaces;

    public PropertyResidentialDetails() {
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public void setPropertyId(UUID propertyId) {
        this.propertyId = propertyId;
    }

    public Property getProperty() {
        return property;
    }

    public void setProperty(Property property) {
        this.property = property;
    }

    public Short getBedrooms() {
        return bedrooms;
    }

    public void setBedrooms(Short bedrooms) {
        this.bedrooms = bedrooms;
    }

    public Short getBathrooms() {
        return bathrooms;
    }

    public void setBathrooms(Short bathrooms) {
        this.bathrooms = bathrooms;
    }

    public Short getBalconies() {
        return balconies;
    }

    public void setBalconies(Short balconies) {
        this.balconies = balconies;
    }

    public String getFurnishingStatus() {
        return furnishingStatus;
    }

    public void setFurnishingStatus(String furnishingStatus) {
        this.furnishingStatus = furnishingStatus;
    }

    public Short getFloorNumber() {
        return floorNumber;
    }

    public void setFloorNumber(Short floorNumber) {
        this.floorNumber = floorNumber;
    }

    public Short getTotalFloors() {
        return totalFloors;
    }

    public void setTotalFloors(Short totalFloors) {
        this.totalFloors = totalFloors;
    }

    public Short getParkingSpaces() {
        return parkingSpaces;
    }

    public void setParkingSpaces(Short parkingSpaces) {
        this.parkingSpaces = parkingSpaces;
    }
}
