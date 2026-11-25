package com.estatevista.inquiry.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.inquiry.entity.Inquiry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for property inquiries. */
public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    @Modifying
    @Query(value = "insert into inquiries "
            + "(id, buyer_id, property_id, assigned_agent_id, subject, message, status, created_at, updated_at, resolved_at) "
            + "values (:id, :buyerId, :propertyId, :assignedAgentId, :subject, :message, 'NEW', now(), now(), null) "
            + "on conflict (buyer_id, property_id) where status in ('NEW', 'IN_PROGRESS') do nothing",
            nativeQuery = true)
    int insertNewIgnoringActiveDuplicate(
            @Param("id") UUID id,
            @Param("buyerId") UUID buyerId,
            @Param("propertyId") UUID propertyId,
            @Param("assignedAgentId") UUID assignedAgentId,
            @Param("subject") String subject,
            @Param("message") String message);

    @Query(value = "select i.id from Inquiry i "
            + "where i.buyer.id = :buyerId "
            + "and (cast(:status as string) is null or i.status = :status)",
            countQuery = "select count(i) from Inquiry i "
                    + "where i.buyer.id = :buyerId "
                    + "and (cast(:status as string) is null or i.status = :status)")
    Page<UUID> findIdsByBuyer(
            @Param("buyerId") UUID buyerId,
            @Param("status") String status,
            Pageable pageable);

    @Query("select i from Inquiry i "
            + "join fetch i.property p "
            + "join fetch i.buyer b "
            + "join fetch i.assignedAgent a "
            + "where i.id in :ids")
    java.util.List<Inquiry> findDetailsByIds(@Param("ids") Collection<UUID> ids);

    @Query("select i from Inquiry i "
            + "join fetch i.property p "
            + "join fetch i.buyer b "
            + "join fetch i.assignedAgent a "
            + "where i.id = :id")
    Optional<Inquiry> findDetailById(@Param("id") UUID id);

    boolean existsByBuyer_IdAndProperty_IdAndStatusIn(
            UUID buyerId, UUID propertyId, Collection<String> statuses);
}
