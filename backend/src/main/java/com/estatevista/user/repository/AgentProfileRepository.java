package com.estatevista.user.repository;

import java.util.UUID;

import com.estatevista.user.entity.AgentProfile;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for agent profiles.
 */
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {
}
