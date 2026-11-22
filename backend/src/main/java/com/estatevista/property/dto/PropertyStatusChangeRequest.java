package com.estatevista.property.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;

/**
 * Status-transition request for the approved lifecycle endpoint (API §3.3).
 *
 * <p>The target is bound as text so unsupported values and the terminal
 * {@code ARCHIVED} value can be rejected by the service with the documented
 * {@code VALIDATION_ERROR} envelope. No actor, timestamp, or current status is
 * accepted from the client. Unknown fields are captured for rejection by
 * {@code UnknownFieldGuard}.</p>
 */
public class PropertyStatusChangeRequest {

    @NotBlank
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
        return Map.copyOf(unknownFields);
    }
}
