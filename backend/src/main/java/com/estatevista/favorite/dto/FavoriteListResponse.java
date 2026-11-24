package com.estatevista.favorite.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Approved paginated favorites item. {@code property} is either the approved
 * public {@code PropertySummaryResponse} or the restricted placeholder DTO.
 */
public record FavoriteListResponse(
        UUID propertyId,
        Instant createdAt,
        Object property) {
}
