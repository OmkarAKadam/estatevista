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

import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exact Origin/Referer validation for CSRF-exempt authentication requests.
 *
 * <p>{@code POST /auth/register} is exempt from the synchronizer token because a
 * visitor has no session yet ({@code SECURITY_ARCHITECTURE.md} SD-53). The
 * approved design compensates with exact Origin/Referer validation on every
 * state-changing request, including the exempt auth POSTs. Rejecting a request
 * that presents neither header prevents a cross-site form post from silently
 * creating an account.</p>
 *
 * <p>This filter does not replace CORS and does not validate the token on
 * non-exempt endpoints; those keep the framework CSRF protection.</p>
 */
public class AuthOriginValidationFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;
    private final ObjectMapper objectMapper;
    private final RequestMatcher matcher;

    public AuthOriginValidationFilter(
            List<String> allowedOrigins,
            ObjectMapper objectMapper,
            RequestMatcher matcher) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.objectMapper = objectMapper;
        this.matcher = matcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String candidate = origin != null && !origin.isBlank()
                ? origin
                : originOf(request.getHeader(HttpHeaders.REFERER));

        if (candidate == null || !isAllowed(candidate)) {
            writeForbidden(request, response);
            return;
        }
        filterChain.doFilter(request, response);
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
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        ErrorCode.FORBIDDEN,
                        "Request origin is not allowed",
                        List.of(),
                        request.getRequestURI()));
    }
}
