package com.estatevista.inquiry.dto;

import java.time.Instant;
import java.util.UUID;

/** Inquiry detail response (API §10.2). */
public record InquiryDetailResponse(
        UUID id,
        UUID propertyId,
        String propertyTitle,
        UUID buyerId,
        UUID assignedAgentId,
        String subject,
        String message,
        String status,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
