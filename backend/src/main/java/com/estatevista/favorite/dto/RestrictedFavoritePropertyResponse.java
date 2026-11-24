package com.estatevista.favorite.dto;

/**
 * Generic placeholder for a retained favorite whose property is no longer
 * public. Only availability and placeholder text are exposed; no unpublished
 * title, price, description, image, address, agent or lifecycle status.
 */
public record RestrictedFavoritePropertyResponse(
        String availability,
        String placeholder) {

    public static RestrictedFavoritePropertyResponse unavailable() {
        return new RestrictedFavoritePropertyResponse(
                "UNAVAILABLE", "Property no longer available");
    }
}
