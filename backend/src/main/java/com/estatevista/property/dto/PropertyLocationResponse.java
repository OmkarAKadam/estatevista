package com.estatevista.property.dto;

import java.math.BigDecimal;

/** Explicit public-safe location projection; no JPA entities are serialized. */
public record PropertyLocationResponse(
        String addressLine,
        String locality,
        String city,
        String state,
        String country,
        String postalCode,
        BigDecimal latitude,
        BigDecimal longitude) {
}
