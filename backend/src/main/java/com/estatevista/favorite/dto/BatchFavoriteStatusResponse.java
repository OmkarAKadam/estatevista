package com.estatevista.favorite.dto;

import java.util.List;

/** Approved {@code data} object for the batched favorite-status endpoint. */
public record BatchFavoriteStatusResponse(List<FavoriteStatusResponse> favorites) {
}
