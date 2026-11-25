package com.estatevista.inquiry.dto;

import java.time.Instant;
import java.util.UUID;

/** Submission response for POST /properties/{propertyId}/inquiries (API §10.1). */
public record InquirySubmissionResponse(
        UUID id,
        UUID propertyId,
        String status,
        Instant createdAt) {
}
