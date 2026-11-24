package com.estatevista.favorite.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.estatevista.favorite.entity.Favorite;
import com.estatevista.property.entity.PropertyStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for buyer favorites.
 *
 * <p>Every query is scoped by the principal-derived buyer ID. The database
 * unique constraint remains the final guard for concurrent additions; the
 * insert below deliberately uses {@code ON CONFLICT DO NOTHING} so a duplicate
 * add never turns the transaction rollback-only.</p>
 */
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    boolean existsByBuyer_IdAndProperty_Id(UUID buyerId, UUID propertyId);

    boolean existsByBuyer_IdAndProperty_IdAndProperty_Status(
            UUID buyerId, UUID propertyId, PropertyStatus status);

    long deleteByBuyer_IdAndProperty_Id(UUID buyerId, UUID propertyId);

    /** One bounded query for the paginated, owner-scoped favorites collection. */
    @Query(value = "select f from Favorite f "
            + "join fetch f.property p "
            + "join fetch p.category "
            + "join fetch p.subtype "
            + "where f.buyer.id = :buyerId",
            countQuery = "select count(f) from Favorite f where f.buyer.id = :buyerId")
    Page<Favorite> findPageByBuyer(
            @Param("buyerId") UUID buyerId, Pageable pageable);

    /** One bounded set-based query for batched status; no per-card lookups. */
    @Query("select f.property.id from Favorite f "
            + "where f.buyer.id = :buyerId "
            + "and f.property.status = :status "
            + "and f.property.id in :propertyIds")
    List<UUID> findPublishedPropertyIds(
            @Param("buyerId") UUID buyerId,
            @Param("status") PropertyStatus status,
            @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * Atomic duplicate-safe insert. The caller holds the property row lock and
     * has already verified publication inside the same transaction.
     */
    @Modifying
    @Query(value = "insert into favorites (id, buyer_id, property_id, created_at) "
            + "values (:id, :buyerId, :propertyId, now()) "
            + "on conflict (buyer_id, property_id) do nothing",
            nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("id") UUID id,
            @Param("buyerId") UUID buyerId,
            @Param("propertyId") UUID propertyId);
}
