package com.estatevista.inquiry.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Buyer inquiry submission request (API §10.2, Stage 6.4).
 *
 * <p>Client-supplied identity, assignment, status, and timestamp fields are
 * rejected via {@link com.estatevista.common.validation.UnknownFieldGuard}.</p>
 */
public class InquirySubmitRequest {

    @Size(max = 200, message = "must be at most 200 characters")
    private String subject;

    @NotBlank(message = "must not be blank")
    @Size(max = 5000, message = "must be at most 5000 characters")
    private String message;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        if (subject == null) {
            this.subject = null;
            return;
        }
        String trimmed = subject.trim();
        this.subject = trimmed.isEmpty() ? null : trimmed;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null ? null : message.trim();
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}
