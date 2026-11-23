package com.estatevista.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.exception.RateLimitExceededException;
import com.estatevista.common.response.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the approved Stage 4 property rate limits from
 * {@code SECURITY_ARCHITECTURE.md} §11.9.
 *
 * <p>Public surfaces are keyed by socket client address; client-supplied
 * forwarded headers are never trusted. Authenticated surfaces are keyed by the
 * server-side session principal, never by a request parameter. Image uploads
 * keep their existing service-level 20/hour/user scope so one upload is counted
 * once. Rejections use the standard API envelope and {@code Retry-After}.</p>
 */
public final class PropertyRateLimitFilter extends OncePerRequestFilter {

    private static final RequestMatcher PUBLIC_SEARCH = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/properties"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/properties/recent"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.GET, "/api/v1/properties/{propertyId}/related"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/property-categories"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/property-subtypes"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/amenities")));

    private static final RequestMatcher PROPERTY_DETAIL = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/properties/{propertyId}"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.GET, "/api/v1/properties/{propertyId}/images")));

    private static final RequestMatcher IMAGE_UPLOAD = PathPatternRequestMatcher.pathPattern(
            HttpMethod.POST, "/api/v1/properties/{propertyId}/images");

    private static final RequestMatcher PROPERTY_MUTATION = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/properties"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/api/v1/properties/{propertyId}"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PATCH, "/api/v1/properties/{propertyId}/status"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PATCH, "/api/v1/properties/{propertyId}/images/{imageId}"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PATCH, "/api/v1/properties/{propertyId}/images/{imageId}/primary"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PATCH, "/api/v1/admin/properties/{propertyId}/assignment"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/api/v1/properties/{propertyId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/v1/properties/{propertyId}"),
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.DELETE, "/api/v1/properties/{propertyId}/images/{imageId}")));

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public PropertyRateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String scope = resolveScope(request);
        if (scope == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            rateLimitService.acquire(scope, resolveIdentity(request));
        } catch (RateLimitExceededException exception) {
            writeRateLimitError(request, response, exception);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String resolveScope(HttpServletRequest request) {
        if (IMAGE_UPLOAD.matches(request)) {
            return null;
        }
        if (PUBLIC_SEARCH.matches(request)) {
            return RateLimitService.SCOPE_PUBLIC_SEARCH;
        }
        if (PROPERTY_DETAIL.matches(request)) {
            return RateLimitService.SCOPE_PROPERTY_DETAIL;
        }
        if (PROPERTY_MUTATION.matches(request)) {
            return isAdmin() ? RateLimitService.SCOPE_ADMIN_MUTATION
                    : RateLimitService.SCOPE_AUTHENTICATED_API;
        }
        return null;
    }

    private static String resolveIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() != null) {
            String principal = authentication.getName();
            if (principal != null && !"anonymousUser".equals(principal)) {
                return "user:" + principal;
            }
        }
        String address = request.getRemoteAddr();
        return "ip:" + (address == null || address.isBlank() ? "unknown" : address);
    }

    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void writeRateLimitError(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimitExceededException exception) throws IOException {
        response.setStatus(ErrorCode.TOO_MANY_REQUESTS.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(
                "Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        ErrorCode.TOO_MANY_REQUESTS,
                        exception.getMessage(),
                        List.of(),
                        request.getRequestURI()));
    }
}
