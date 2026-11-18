package com.estatevista.common.exception;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.response.ApiErrorResponse;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Central exception handling for every REST controller.
 *
 * <p>Converts framework and application exceptions into the error envelope from
 * {@code docs/API_SPECIFICATION.md} §17. Responses never contain stack traces,
 * exception class names, SQL text, bean names, or internal file paths.</p>
 *
 * <p>Expected client errors are logged at debug level; unexpected server errors
 * are logged with the exception at error level for operators. Request bodies and
 * headers are never logged here.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectProvider<AuditEventService> auditEventService;

    private static final String GENERIC_FIELD_MESSAGE = "Invalid value";

    /** Keeps standalone handler tests usable when no audit provider is present. */
    public GlobalExceptionHandler() {
        this(null);
    }

    @Autowired
    public GlobalExceptionHandler(ObjectProvider<AuditEventService> auditEventService) {
        this.auditEventService = auditEventService;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException exception, HttpServletRequest request) {
        if (exception.getErrorCode() == ErrorCode.FORBIDDEN) {
            auditAccessDenied(request);
        }
        if (exception.getErrorCode().status().is5xxServerError()) {
            log.error("API error {} at {} {}",
                    exception.getErrorCode().name(),
                    request.getMethod(),
                    request.getRequestURI(),
                    exception);
        } else {
            log.debug("Client error {} at {} {}: {}",
                    exception.getErrorCode().name(),
                    request.getMethod(),
                    request.getRequestURI(),
                    exception.getMessage());
        }
        return respond(
                exception.getErrorCode(),
                exception.getMessage(),
                exception.getDetails(),
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.add(new FieldErrorDetail(
                    fieldError.getField(),
                    messageOrDefault(fieldError)));
        }
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                details.add(new FieldErrorDetail(
                        error.getObjectName(),
                        messageOrDefault(error))));
        return validationResponse(details, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<FieldErrorDetail> details = new ArrayList<>();
        exception.getParameterValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error ->
                        details.add(new FieldErrorDetail(
                                parameterName(result.getMethodParameter()),
                                messageOrDefault(error)))));
        exception.getCrossParameterValidationResults().forEach(error ->
                details.add(new FieldErrorDetail("request", messageOrDefault(error))));
        return validationResponse(details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldErrorDetail> details = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(
                        lastPathNode(violation.getPropertyPath().toString()),
                        defaultIfBlank(violation.getMessage())))
                .toList();
        return validationResponse(details, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception exception, HttpServletRequest request) {
        // Log only the failure category. Framework parse messages can embed an
        // excerpt of the rejected body, which must never reach the logs (SD-44,
        // Security Architecture §11.13, §11.17, and §14.9).
        log.debug("Malformed request at {} {} ({})",
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName());
        return respond(ErrorCode.BAD_REQUEST, null, List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, null, List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, List.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, null, List.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            Exception exception, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, null, List.of(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        ErrorCode errorCode = ErrorCode.fromStatus(status.value());
        String message = status.is5xxServerError() ? null : exception.getReason();
        return respond(errorCode, message, List.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        log.warn("Access denied at {} {}", request.getMethod(), request.getRequestURI());
        auditAccessDenied(request);
        return respond(ErrorCode.FORBIDDEN, null, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception at {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return respond(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
    }

    private ResponseEntity<ApiErrorResponse> validationResponse(
            List<FieldErrorDetail> details, HttpServletRequest request) {
        log.debug("Validation failed at {} {}: {} detail(s)",
                request.getMethod(),
                request.getRequestURI(),
                details.size());
        return respond(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                details,
                request);
    }

    private void auditAccessDenied(HttpServletRequest request) {
        AuditEventService audit = auditEventService == null
                ? null : auditEventService.getIfAvailable();
        if (audit != null) {
            audit.recordAccessDenied(request);
        }
    }

    private static ResponseEntity<ApiErrorResponse> respond(
            ErrorCode errorCode,
            String message,
            List<FieldErrorDetail> details,
            HttpServletRequest request) {
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(errorCode, message, details, requestPath(request)));
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }

    private static String messageOrDefault(MessageSourceResolvable resolvable) {
        return defaultIfBlank(resolvable.getDefaultMessage());
    }

    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name == null || name.isBlank() ? "request" : name;
    }

    private static String lastPathNode(String path) {
        if (path == null || path.isBlank()) {
            return "request";
        }
        int separator = path.lastIndexOf('.');
        return separator >= 0 && separator < path.length() - 1
                ? path.substring(separator + 1)
                : path;
    }

    private static String defaultIfBlank(String value) {
        return value == null || value.isBlank() ? GENERIC_FIELD_MESSAGE : value;
    }
}
