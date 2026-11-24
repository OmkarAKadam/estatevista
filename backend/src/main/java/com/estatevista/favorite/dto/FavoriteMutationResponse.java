package com.estatevista.favorite.dto;

import java.util.UUID;

/** Approved data object for favorite POST responses (API §9.1). */
public record FavoriteMutationResponse(UUID propertyId, boolean isFavorite) {
}
