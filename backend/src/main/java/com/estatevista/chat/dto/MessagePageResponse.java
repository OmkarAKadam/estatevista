package com.estatevista.chat.dto;

import java.util.List;

/**
 * Cursor-paginated history envelope. Metadata is a sibling of {@code data},
 * never nested inside it (API §11.2).
 */
public record MessagePageResponse(boolean success, List<MessageResponse> data, MessagePageMeta meta) {

    public record MessagePageMeta(int limit, String nextCursor, boolean hasMore) {
    }

    public static MessagePageResponse of(
            List<MessageResponse> messages, int limit, String nextCursor, boolean hasMore) {
        return new MessagePageResponse(true, List.copyOf(messages), new MessagePageMeta(limit, nextCursor, hasMore));
    }
}
