package com.estatevista.property.dto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Residential-specific write payload for the existing detail table. */
public class ResidentialDetailsRequest {

    @Min(0)
    private Short bedrooms;

    @Min(0)
    private Short bathrooms;

    @Min(0)
    private Short balconies;

    @Min(0)
    private Short floorNumber;

    @Min(0)
    private Short totalFloors;

    @Min(0)
    private Short parkingSpaces;

    @Size(max = 30)
    private String furnishingStatus;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public ResidentialDetailsRequest() {
    }

    public Short getBedrooms() {
        return bedrooms;
    }

    public void setBedrooms(Short bedrooms) {
        this.bedrooms = bedrooms;
        presentFields.add("bedrooms");
    }

    public Short getBathrooms() {
        return bathrooms;
    }

    public void setBathrooms(Short bathrooms) {
        this.bathrooms = bathrooms;
        presentFields.add("bathrooms");
    }

    public Short getBalconies() {
        return balconies;
    }

    public void setBalconies(Short balconies) {
        this.balconies = balconies;
        presentFields.add("balconies");
    }

    public Short getFloorNumber() {
        return floorNumber;
    }

    public void setFloorNumber(Short floorNumber) {
        this.floorNumber = floorNumber;
        presentFields.add("floorNumber");
    }

    public Short getTotalFloors() {
        return totalFloors;
    }

    public void setTotalFloors(Short totalFloors) {
        this.totalFloors = totalFloors;
        presentFields.add("totalFloors");
    }

    public Short getParkingSpaces() {
        return parkingSpaces;
    }

    public void setParkingSpaces(Short parkingSpaces) {
        this.parkingSpaces = parkingSpaces;
        presentFields.add("parkingSpaces");
    }

    public String getFurnishingStatus() {
        return furnishingStatus;
    }

    public void setFurnishingStatus(String furnishingStatus) {
        this.furnishingStatus = furnishingStatus;
        presentFields.add("furnishingStatus");
    }

    public boolean hasField(String field) {
        return presentFields.contains(field);
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}
