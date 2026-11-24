package com.estatevista.favorite.dto;

import java.util.UUID;

/** One authoritative favorite-state entry for a single buyer. */
public record FavoriteStatusResponse(UUID propertyId, boolean isFavorite) {
}
