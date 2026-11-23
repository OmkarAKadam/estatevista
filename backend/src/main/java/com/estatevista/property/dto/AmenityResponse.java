package com.estatevista.property.dto;

import java.util.UUID;

/** Public amenity identifier/name projection. */
public record AmenityResponse(UUID id, String name) {
}
