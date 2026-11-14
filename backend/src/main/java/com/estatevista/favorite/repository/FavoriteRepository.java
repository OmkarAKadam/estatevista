package com.estatevista.favorite.repository;

import java.util.UUID;

import com.estatevista.favorite.entity.Favorite;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for buyer favorites.
 */
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {
}
