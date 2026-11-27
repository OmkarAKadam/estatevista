package com.estatevista.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.estatevista.chat.entity.Message;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;

/**
 * Opaque cursor encoding of {@code (conversationId, sentAt, id)}.
 *
 * <p>No message content is encoded. A checksum detects corruption or casual
 * tampering, and the service additionally verifies that the encoded identity
 * exists in the same conversation before using it.</p>
 */
public final class ChatCursor {

    private static final String DOMAIN = "estatevista-chat-cursor-v1|";

    private ChatCursor() {
    }

    public static String encode(UUID conversationId, Message message) {
        String payload = conversationId + "|" + message.getSentAt() + "|" + message.getId();
        String encoded = payload + "|" + checksum(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encoded.getBytes(StandardCharsets.UTF_8));
    }

    static Decoded decode(String cursor, UUID conversationId) {
        if (cursor == null || cursor.isBlank()) {
            throw invalid();
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 4 || !checksum(parts[0] + "|" + parts[1] + "|" + parts[2]).equals(parts[3])) {
            throw invalid();
        }
        try {
            UUID encodedConversation = UUID.fromString(parts[0]);
            if (!conversationId.equals(encodedConversation)) {
                throw invalid();
            }
            return new Decoded(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    record Decoded(Instant sentAt, UUID messageId) {
    }

    private static String checksum(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((DOMAIN + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.BAD_REQUEST, "Invalid message cursor");
    }
}
