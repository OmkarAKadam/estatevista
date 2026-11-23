package com.estatevista.property.dto;

import java.util.UUID;

/**
 * Safe image metadata projection defined by PROPERTY_API_DESIGN.md §3.6.
 *
 * <p>No storage credential, signature, private identifier, filesystem path, or
 * provider error detail is exposed. {@code storagePublicId} is deliberately
 * absent.</p>
 */
public record PropertyImageResponse(
        UUID id,
        String imageUrl,
        String altText,
        Integer sortOrder,
        Boolean isPrimary) {
}
