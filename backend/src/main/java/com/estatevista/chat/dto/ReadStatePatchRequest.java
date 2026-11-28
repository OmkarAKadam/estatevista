package com.estatevista.chat.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

/** Monotonic read-position update; the client never supplies a user or status. */
public class ReadStatePatchRequest {

    @NotNull(message = "lastReadMessageId must not be null")
    private UUID lastReadMessageId;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public UUID getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(UUID lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unknownFields));
    }
}
