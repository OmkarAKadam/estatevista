package com.estatevista.property.dto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Commercial-specific write payload for the existing detail table. */
public class CommercialDetailsRequest {

    @Min(0)
    private Short parkingSpaces;

    @Min(0)
    private Short floorNumber;

    @Min(0)
    private Short totalFloors;

    @Min(0)
    private Short washrooms;

    private Boolean powerBackup;

    @Size(max = 255)
    private String suitableFor;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public CommercialDetailsRequest() {
    }

    public Short getParkingSpaces() {
        return parkingSpaces;
    }

    public void setParkingSpaces(Short parkingSpaces) {
        this.parkingSpaces = parkingSpaces;
        presentFields.add("parkingSpaces");
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

    public Short getWashrooms() {
        return washrooms;
    }

    public void setWashrooms(Short washrooms) {
        this.washrooms = washrooms;
        presentFields.add("washrooms");
    }

    public Boolean getPowerBackup() {
        return powerBackup;
    }

    public void setPowerBackup(Boolean powerBackup) {
        this.powerBackup = powerBackup;
        presentFields.add("powerBackup");
    }

    public String getSuitableFor() {
        return suitableFor;
    }

    public void setSuitableFor(String suitableFor) {
        this.suitableFor = suitableFor;
        presentFields.add("suitableFor");
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
