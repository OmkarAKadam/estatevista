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
 * Commercial-specific property attributes ({@code property_commercial_details},
 * DATABASE_DESIGN.md §6.5).
 */
@Entity
@Table(name = "property_commercial_details")
public class PropertyCommercialDetails {

    @Id
    @Column(name = "property_id")
    private UUID propertyId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "parking_spaces")
    private Short parkingSpaces;

    @Column(name = "floor_number")
    private Short floorNumber;

    @Column(name = "total_floors")
    private Short totalFloors;

    @Column(name = "washrooms")
    private Short washrooms;

    @Column(name = "power_backup")
    private Boolean powerBackup;

    @Column(name = "suitable_for", length = 255)
    private String suitableFor;

    public PropertyCommercialDetails() {
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

    public Short getParkingSpaces() {
        return parkingSpaces;
    }

    public void setParkingSpaces(Short parkingSpaces) {
        this.parkingSpaces = parkingSpaces;
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

    public Short getWashrooms() {
        return washrooms;
    }

    public void setWashrooms(Short washrooms) {
        this.washrooms = washrooms;
    }

    public Boolean getPowerBackup() {
        return powerBackup;
    }

    public void setPowerBackup(Boolean powerBackup) {
        this.powerBackup = powerBackup;
    }

    public String getSuitableFor() {
        return suitableFor;
    }

    public void setSuitableFor(String suitableFor) {
        this.suitableFor = suitableFor;
    }
}
