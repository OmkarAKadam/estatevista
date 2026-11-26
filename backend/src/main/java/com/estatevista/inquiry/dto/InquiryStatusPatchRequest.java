package com.estatevista.inquiry.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Stage 6.5 inquiry status patch request (API §10.2).
 *
 * <p>Only the server-approved target status is client controlled. All other
 * fields are captured and rejected by
 * {@link com.estatevista.common.validation.UnknownFieldGuard}.</p>
 */
public class InquiryStatusPatchRequest {

    @NotBlank(message = "must not be blank")
    @Pattern(
            regexp = "NEW|IN_PROGRESS|RESOLVED|CLOSED",
            message = "must be a supported inquiry status")
    private String status;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        // Map.copyOf rejects null values; explicit JSON nulls must still reach
        // UnknownFieldGuard and produce a 400 instead of a 500.
        return Collections.unmodifiableMap(new LinkedHashMap<>(unknownFields));
    }
}
