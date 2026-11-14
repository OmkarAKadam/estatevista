package com.estatevista.common.exception;

import java.util.List;

import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

/**
 * Base runtime exception for expected, client-safe API failures.
 *
 * <p>Business modules must throw a subclass (or this type with a specific
 * {@link ErrorCode}) instead of returning ad-hoc error payloads. The global
 * exception handler converts it into the standard error envelope.</p>
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldErrorDetail> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldErrorDetail> details) {
        this(errorCode, message, details, null);
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, List.of(), cause);
    }

    public ApiException(
            ErrorCode errorCode,
            String message,
            List<FieldErrorDetail> details,
            Throwable cause) {
        super(message, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldErrorDetail> getDetails() {
        return details;
    }
}
