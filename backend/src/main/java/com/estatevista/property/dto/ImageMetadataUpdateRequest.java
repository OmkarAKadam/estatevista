package com.estatevista.property.dto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Metadata-only image update payload (PROPERTY_API_DESIGN.md §3.5).
 *
 * <p>Provider identity, URLs, ownership, primary state, and timestamps are not
 * writable through this request. Unknown fields are captured so the controller
 * can reject mass-assignment attempts through the shared guard.</p>
 */
public class ImageMetadataUpdateRequest {

    @Size(max = 255)
    private String altText;

    @Min(0)
    private Integer sortOrder;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public String getAltText() {
        return altText;
    }

    public void setAltText(String altText) {
        this.altText = altText;
        presentFields.add("altText");
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
        presentFields.add("sortOrder");
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
