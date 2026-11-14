package com.estatevista.property.repository;

import java.util.UUID;

import com.estatevista.property.entity.PropertyResidentialDetails;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for residential property details.
 */
public interface PropertyResidentialDetailsRepository
        extends JpaRepository<PropertyResidentialDetails, UUID> {
}
