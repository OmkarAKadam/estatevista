package com.estatevista.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, transport-level error codes shared by the REST API.
 *
 * <p>These codes describe protocol and request failures only. Business-specific
 * error codes belong to the module that owns the business rule and must not be
 * added here prematurely.</p>
 *
 * <p>The {@code name()} of each constant is the value exposed in the
 * {@code error.code} field of the API error envelope.</p>
 */
public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "The request could not be processed"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "The supplied data is invalid"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this resource"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state of the resource"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request payload is too large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The media type is not supported"),
    UNPROCESSABLE_ENTITY(HttpStatus.UNPROCESSABLE_CONTENT, "The request is valid but cannot be processed"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * Maps a raw HTTP status to the closest transport-level error code.
     * Unknown statuses fall back to {@link #INTERNAL_ERROR} so a response never
     * discloses an arbitrary status-derived message.
     */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 429 -> TOO_MANY_REQUESTS;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
