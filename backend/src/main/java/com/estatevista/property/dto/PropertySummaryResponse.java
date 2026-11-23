package com.estatevista.property.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.PropertyStatus;

/** List/search projection defined by API §7.2. */
public record PropertySummaryResponse(
        UUID id,
        String title,
        String slug,
        String category,
        String subtype,
        ListingType listingType,
        BigDecimal price,
        String currencyCode,
        String city,
        String locality,
        BigDecimal areaValue,
        String areaUnit,
        Short bedrooms,
        Short bathrooms,
        String primaryImageUrl,
        PropertyStatus status) {
}
