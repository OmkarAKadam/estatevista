package com.estatevista.common.response;

import java.time.Instant;
import java.util.List;

import com.estatevista.common.exception.ErrorCode;

/**
 * Standard error envelope defined by {@code docs/API_SPECIFICATION.md} §17.
 *
 * <p>Every error response has the same shape. Validation failures add field
 * details; all other failures return an empty {@code details} list. No stack
 * trace, SQL fragment, exception class, or internal path is ever included.</p>
 */
public record ApiErrorResponse(
        boolean success,
        ErrorDetail error,
        Instant timestamp,
        String path) {

    public record ErrorDetail(String code, String message, List<FieldErrorDetail> details) {
        public ErrorDetail {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record FieldErrorDetail(String field, String message) {
    }

    public static ApiErrorResponse of(
            ErrorCode errorCode,
            String message,
            List<FieldErrorDetail> details,
            String path) {
        String safeMessage = (message == null || message.isBlank())
                ? errorCode.defaultMessage()
                : message;
        return new ApiErrorResponse(
                false,
                new ErrorDetail(errorCode.name(), safeMessage, details),
                Instant.now(),
                path);
    }
}
