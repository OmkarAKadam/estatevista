package com.estatevista.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard success envelope defined by {@code docs/API_SPECIFICATION.md}.
 *
 * <p>Successful responses use {@code { "success": true, "message"?: string,
 * "data": ... } }. The message is omitted when it is not meaningful, so the
 * shape stays compatible with the documented current-user response. Error
 * responses keep using {@link ApiErrorResponse}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
