package com.estatevista.property.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.estatevista.property.entity.ListingType;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shared PATCH/PUT payload (API §3.2).
 *
 * <p>Category, subtype, assignment, status, IDs, audit columns, timestamps, and
 * image fields are deliberately absent. Present-field tracking preserves PATCH
 * semantics and lets PUT require the documented mandatory fields.</p>
 */
public class PropertyUpdateRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 10000)
    private String description;

    private ListingType listingType;

    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 2)
    private BigDecimal price;

    @Pattern(regexp = "[A-Z]{3}")
    private String currencyCode;

    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 10, fraction = 2)
    private BigDecimal areaValue;

    @Size(max = 20)
    private String areaUnit;

    @Valid
    private PropertyLocationRequest location;

    @Valid
    private ResidentialDetailsRequest residentialDetails;

    @Valid
    private CommercialDetailsRequest commercialDetails;

    private List<UUID> amenityIds;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public PropertyUpdateRequest() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        presentFields.add("title");
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        presentFields.add("description");
    }

    public ListingType getListingType() {
        return listingType;
    }

    public void setListingType(ListingType listingType) {
        this.listingType = listingType;
        presentFields.add("listingType");
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
        presentFields.add("price");
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
        presentFields.add("currencyCode");
    }

    public BigDecimal getAreaValue() {
        return areaValue;
    }

    public void setAreaValue(BigDecimal areaValue) {
        this.areaValue = areaValue;
        presentFields.add("areaValue");
    }

    public String getAreaUnit() {
        return areaUnit;
    }

    public void setAreaUnit(String areaUnit) {
        this.areaUnit = areaUnit;
        presentFields.add("areaUnit");
    }

    public PropertyLocationRequest getLocation() {
        return location;
    }

    public void setLocation(PropertyLocationRequest location) {
        this.location = location;
        presentFields.add("location");
    }

    public ResidentialDetailsRequest getResidentialDetails() {
        return residentialDetails;
    }

    public void setResidentialDetails(ResidentialDetailsRequest residentialDetails) {
        this.residentialDetails = residentialDetails;
        presentFields.add("residentialDetails");
    }

    public CommercialDetailsRequest getCommercialDetails() {
        return commercialDetails;
    }

    public void setCommercialDetails(CommercialDetailsRequest commercialDetails) {
        this.commercialDetails = commercialDetails;
        presentFields.add("commercialDetails");
    }

    public List<UUID> getAmenityIds() {
        return amenityIds;
    }

    public void setAmenityIds(List<UUID> amenityIds) {
        this.amenityIds = amenityIds;
        presentFields.add("amenityIds");
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public boolean hasField(String field) {
        return presentFields.contains(field);
    }

    public boolean hasAnyUpdate() {
        return !presentFields.isEmpty();
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}
