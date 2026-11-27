package com.estatevista.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Validates exact origin allowlists shared by the CORS configuration and the
 * WebSocket handshake.
 *
 * <p>Only absolute {@code http}/{@code https} origins without a path, query,
 * fragment, or user info are accepted. This rejects wildcards, blank entries,
 * and unresolved {@code ${...}} placeholders at startup instead of silently
 * building an allowlist that can never match a browser origin.</p>
 */
public final class AllowedOrigins {

    private AllowedOrigins() {
    }

    public static List<String> requireExplicit(List<String> origins, String propertyName) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException(
                    propertyName + " must contain at least one explicit origin");
        }
        for (String origin : origins) {
            requireValid(origin, propertyName);
        }
        return List.copyOf(origins);
    }

    private static void requireValid(String origin, String propertyName) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " must not contain blank origins");
        }
        if ("*".equals(origin)) {
            throw new IllegalStateException(
                    "Wildcard origins are not permitted in " + propertyName);
        }

        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    propertyName + " contains an invalid origin: " + origin, exception);
        }

        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
        boolean emptyPath = uri.getRawPath() == null || uri.getRawPath().isEmpty();
        if (!httpScheme
                || uri.getHost() == null
                || !emptyPath
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getUserInfo() != null) {
            throw new IllegalStateException(
                    propertyName + " contains an invalid origin: " + origin);
        }
    }
}
