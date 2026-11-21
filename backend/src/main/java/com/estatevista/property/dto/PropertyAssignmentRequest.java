package com.estatevista.property.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

/**
 * Admin-only primary-agent reassignment request (API §3.4).
 *
 * <p>{@code primaryAgentId} is a privileged assignment target, never an actor
 * identity. Other fields, including client-supplied audit or ownership fields,
 * are captured and rejected by {@code UnknownFieldGuard}.</p>
 */
public class PropertyAssignmentRequest {

    @NotNull
    private UUID primaryAgentId;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public UUID getPrimaryAgentId() {
        return primaryAgentId;
    }

    public void setPrimaryAgentId(UUID primaryAgentId) {
        this.primaryAgentId = primaryAgentId;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}
