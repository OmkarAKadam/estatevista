package com.estatevista.chat.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Conversation creation deliberately accepts no fields. Any client-supplied
 * buyer, agent, status, or initial message is captured only so the controller
 * can reject it with a generic validation response.
 */
public class ConversationCreateRequest {

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unknownFields));
    }
}
