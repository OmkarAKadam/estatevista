package com.estatevista.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CorsConfigTest {

    private final CorsConfig corsConfig = new CorsConfig();

    @Test
    void exposesCredentialedExactOriginConfigurationForApiAndActuatorHealth() {
        CorsConfigurationSource source = corsConfig.corsConfigurationSource(properties(
                List.of("http://localhost:5500", "https://app.example.com")));

        CorsConfiguration api = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/v1/health"));
        CorsConfiguration actuator = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/actuator/health"));

        assertThat(api).isNotNull();
        assertThat(api.getAllowedOrigins())
                .containsExactly("http://localhost:5500", "https://app.example.com");
        assertThat(api.getAllowCredentials()).isTrue();
        assertThat(api.getMaxAge()).isEqualTo(600L);
        assertThat(api.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(api.getAllowedHeaders())
                .containsExactly("Content-Type", "X-CSRF-TOKEN");
        assertThat(api.getExposedHeaders()).containsExactly("X-CSRF-TOKEN");
        assertThat(actuator).isNotNull();
        assertThat(actuator.getAllowedOrigins())
                .containsExactly("http://localhost:5500", "https://app.example.com");
    }

    @Test
    void rejectsWildcardOriginsThroughTheSharedValidator() {
        assertThatThrownBy(() -> corsConfig.corsConfigurationSource(properties(List.of("*"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wildcard origins are not permitted");
    }

    @Test
    void rejectsAnEmptyOriginListThroughTheSharedValidator() {
        assertThatThrownBy(() -> corsConfig.corsConfigurationSource(properties(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one explicit origin");
    }

    private static AppProperties properties(List<String> origins) {
        return new AppProperties(
                new AppProperties.Cors(origins),
                new AppProperties.Websocket("/ws", origins));
    }
}
