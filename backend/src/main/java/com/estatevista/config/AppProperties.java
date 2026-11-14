package com.estatevista.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized application-level configuration bound from {@code app.*}.
 *
 * <p>Origins are supplied as environment-specific configuration so the same
 * artifact can run locally and in production without a wildcard CORS rule.</p>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Websocket websocket) {

    public record Cors(List<String> allowedOrigins) {
    }

    public record Websocket(String endpoint, List<String> allowedOrigins) {
    }
}
