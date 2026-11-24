package com.estatevista.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures private API responses are not stored by shared caches.
 *
 * <p>Public property discovery and reference-data GET responses keep their
 * existing caching behavior. All other API responses default to
 * {@code Cache-Control: no-store} unless a cache header is already present.</p>
 */
public class PrivateResponseNoStoreFilter extends OncePerRequestFilter {

    private static final RequestMatcher PUBLIC_CACHEABLE_GET = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/properties"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/properties/recent"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/properties/{propertyId}"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/properties/{propertyId}/related"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/properties/{propertyId}/images"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/property-categories"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/property-subtypes"),
            PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.GET, "/api/v1/amenities"));

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        if (!path.startsWith("/api/")) {
            return true;
        }
        return PUBLIC_CACHEABLE_GET.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
        // Private API responses must never be stored by shared caches. Any
        // weaker or conflicting Cache-Control value is overridden (SD-F4).
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
