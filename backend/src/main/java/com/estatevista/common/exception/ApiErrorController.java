package com.estatevista.common.exception;

import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.estatevista.common.response.ApiErrorResponse;

/**
 * Replaces Spring Boot's default error page with the standard API error
 * envelope.
 *
 * <p>This covers failures raised outside a controller, such as servlet-container
 * errors and routing failures. Controller exceptions are handled by
 * {@link GlobalExceptionHandler}. The container-provided error message is never
 * returned; only the mapped status and safe default message are exposed.</p>
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiErrorResponse> handleError(HttpServletRequest request) {
        int rawStatus = statusCode(request);
        ErrorCode errorCode = ErrorCode.fromStatus(rawStatus);
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(
                        errorCode,
                        null,
                        List.of(),
                        requestedPath(request)));
    }

    private static int statusCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (attribute instanceof Integer status && status >= 400 && status <= 599) {
            return status;
        }
        return 500;
    }

    private static String requestedPath(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (attribute instanceof String uri && !uri.isBlank()) {
            return uri;
        }
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }
}
