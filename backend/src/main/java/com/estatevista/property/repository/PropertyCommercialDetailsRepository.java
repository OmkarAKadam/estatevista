package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.PropertyCommercialDetails;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for commercial property details.
 */
public interface PropertyCommercialDetailsRepository
        extends JpaRepository<PropertyCommercialDetails, UUID> {
}
