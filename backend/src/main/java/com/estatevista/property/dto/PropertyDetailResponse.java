package com.estatevista.property.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.PropertyStatus;

/** Single-resource projection defined by API §3.6 and §4.2. */
public record PropertyDetailResponse(
        UUID id,
        String title,
        String slug,
        String description,
        String category,
        String subtype,
        ListingType listingType,
        BigDecimal price,
        String currencyCode,
        BigDecimal areaValue,
        String areaUnit,
        PropertyLocationResponse location,
        ResidentialDetailsResponse residentialDetails,
        CommercialDetailsResponse commercialDetails,
        List<AmenityResponse> amenities,
        PublicAgentResponse primaryAgent,
        String primaryImageUrl,
        PropertyStatus status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {
}
