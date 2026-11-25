package com.estatevista.inquiry.dto;

import java.time.Instant;
import java.util.UUID;

/** Inquiry list item response (API §10.2); message is excluded from list views. */
public record InquiryListItemResponse(
        UUID id,
        UUID propertyId,
        String propertyTitle,
        UUID buyerId,
        UUID assignedAgentId,
        String subject,
        String status,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
