package com.estatevista.audit.repository;

import java.util.UUID;

import com.estatevista.audit.entity.AuditLog;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for append-only audit history.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
