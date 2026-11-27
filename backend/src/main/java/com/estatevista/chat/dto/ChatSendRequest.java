package com.estatevista.chat.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Approved {@code /app/chat.send} payload (API §20.4).
 *
 * <p>Sender identity, message ID, timestamp, type and audit fields are never
 * accepted. Any such field lands in {@link #unknownFields()} and is rejected
 * before persistence.</p>
 */
public class ChatSendRequest {

    @NotNull(message = "conversationId must not be null")
    private UUID conversationId;

    @NotBlank(message = "content must not be blank")
    @Size(max = 2000, message = "content must be at most 2000 characters")
    private String content;

    @NotNull(message = "idempotencyKey must not be null")
    private UUID idempotencyKey;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unknownFields));
    }
}
