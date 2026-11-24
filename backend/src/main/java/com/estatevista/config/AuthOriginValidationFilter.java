package com.estatevista.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exact Origin/Referer validation for state-changing browser requests.
 *
 * <p>Compensates for CSRF-exempt authentication POSTs and provides an
 * independent defense-in-depth check on every other mutating request
 * ({@code SECURITY_ARCHITECTURE.md} SD-53, SD-F1). Both the synchronizer token
 * and the origin check must pass on state-changing requests; neither replaces
 * the other.</p>
 *
 * <p>A request is rejected when it presents a disallowed, malformed, or
 * ambiguous origin. When {@code Origin} is absent, an exact {@code Referer}
 * origin is accepted. When neither header is present, state-changing browser
 * requests are rejected.</p>
 */
public class AuthOriginValidationFilter extends OncePerRequestFilter {

    private static final java.util.Set<String> SAFE_METHODS =
            java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final List<String> allowedOrigins;
    private final ObjectMapper objectMapper;
    private final RequestMatcher matcher;
    private final ObjectProvider<AuditEventService> auditEventService;

    public AuthOriginValidationFilter(
            List<String> allowedOrigins,
            ObjectMapper objectMapper,
            RequestMatcher matcher,
            ObjectProvider<AuditEventService> auditEventService) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.objectMapper = objectMapper;
        this.matcher = matcher;
        this.auditEventService = auditEventService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        java.util.List<String> originHeaders = java.util.Collections.list(
                request.getHeaders(HttpHeaders.ORIGIN));
        if (originHeaders.size() > 1) {
            reject(request, response);
            return;
        }

        String origin = originHeaders.isEmpty() ? null : originHeaders.get(0);
        String candidate;
        if (origin != null && !origin.isBlank()) {
            candidate = origin.trim();
        } else {
            java.util.List<String> refererHeaders = java.util.Collections.list(
                    request.getHeaders(HttpHeaders.REFERER));
            if (refererHeaders.size() > 1) {
                reject(request, response);
                return;
            }
            candidate = refererHeaders.isEmpty() ? null : originOf(refererHeaders.get(0));
        }

        if (candidate == null || !isAllowed(candidate)) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AuditEventService audit = auditEventService == null
                ? null
                : auditEventService.getIfAvailable();
        if (audit != null) {
            audit.recordOriginRejected(request);
        }
        writeForbidden(request, response);
    }

    private boolean isAllowed(String origin) {
        return allowedOrigins.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(origin));
    }

    private static String originOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(referer);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder origin = new StringBuilder()
                    .append(uri.getScheme())
                    .append("://")
                    .append(uri.getHost());
            if (uri.getPort() != -1) {
                origin.append(':').append(uri.getPort());
            }
            return origin.toString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        ErrorCode.FORBIDDEN,
                        "Request origin is not allowed",
                        List.of(),
                        request.getRequestURI()));
    }
}
