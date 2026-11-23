package com.estatevista.property.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Nested location payload shared by create and update requests.
 *
 * <p>Unknown fields are captured so the controller can reject them through the
 * existing {@code UnknownFieldGuard} instead of silently accepting unsupported
 * writes.</p>
 */
public class PropertyLocationRequest {

    @Size(max = 255)
    private String addressLine;

    @Size(max = 120)
    private String locality;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String country;

    @Size(max = 20)
    private String postalCode;

    @DecimalMin(value = "-90.0000000")
    @DecimalMax(value = "90.0000000")
    private BigDecimal latitude;

    @DecimalMin(value = "-180.0000000")
    @DecimalMax(value = "180.0000000")
    private BigDecimal longitude;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public PropertyLocationRequest() {
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
        presentFields.add("addressLine");
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
        presentFields.add("locality");
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
        presentFields.add("city");
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
        presentFields.add("state");
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
        presentFields.add("country");
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
        presentFields.add("postalCode");
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
        presentFields.add("latitude");
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
        presentFields.add("longitude");
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public boolean hasField(String field) {
        return presentFields.contains(field);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}
