package com.estatevista.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Exact-origin, credentialed CORS for the separately hosted frontend.
 *
 * <p>The approved deployment keeps the frontend and API on different origins
 * but the same registrable domain (Option C). Credentialed CORS therefore
 * requires explicit origins; a wildcard origin is rejected at startup.</p>
 */
@Configuration
public class CorsConfig {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private static final List<String> ALLOWED_HEADERS =
            List.of("Content-Type", "X-CSRF-TOKEN");

    private static final List<String> EXPOSED_HEADERS = List.of("X-CSRF-TOKEN");

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        List<String> origins = AllowedOrigins.requireExplicit(
                properties.cors().allowedOrigins(), "app.cors.allowed-origins");

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/health", configuration);
        return source;
    }
}
