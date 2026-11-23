package com.estatevista.property.dto;

import java.util.UUID;

import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertySubtype;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO-only projection for the three approved public reference-data lookups
 * (PROPERTY_API_DESIGN.md §4.14-§4.16).
 *
 * <p>A single record keeps the response family consistent while the null fields
 * are omitted per endpoint: categories expose {@code id, name}; subtypes expose
 * {@code id, categoryId, name}; amenities expose {@code id, name} and the optional
 * public {@code description}. No JPA entity, audit field, or relationship is
 * serialized.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceDataResponse(
        UUID id,
        UUID categoryId,
        String name,
        String description) {

    public static ReferenceDataResponse fromCategory(PropertyCategory category) {
        return new ReferenceDataResponse(category.getId(), null, category.getName(), null);
    }

    public static ReferenceDataResponse fromSubtype(PropertySubtype subtype) {
        return new ReferenceDataResponse(
                subtype.getId(),
                subtype.getCategory() == null ? null : subtype.getCategory().getId(),
                subtype.getName(),
                null);
    }

    public static ReferenceDataResponse fromAmenity(Amenity amenity) {
        return new ReferenceDataResponse(
                amenity.getId(), null, amenity.getName(), amenity.getDescription());
    }
}
