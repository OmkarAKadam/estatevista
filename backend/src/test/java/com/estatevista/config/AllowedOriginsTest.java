package com.estatevista.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

class AllowedOriginsTest {

    @Test
    void acceptsExactHttpAndHttpsOrigins() {
        List<String> origins = List.of("http://localhost:5500", "https://app.example.com");

        assertThat(AllowedOrigins.requireExplicit(origins, "app.cors.allowed-origins"))
                .containsExactlyElementsOf(origins);
    }

    @Test
    void rejectsEmptyAndNullLists() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(List.of(), "app.cors.allowed-origins"))
                .withMessageContaining("at least one explicit origin");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(null, "app.cors.allowed-origins"))
                .withMessageContaining("at least one explicit origin");
    }

    @Test
    void rejectsWildcard() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(List.of("*"), "app.cors.allowed-origins"))
                .withMessageContaining("Wildcard origins are not permitted");
    }

    @Test
    void rejectsUnresolvedPlaceholders() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(
                        List.of("${APP_CORS_ALLOWED_ORIGINS}"), "app.cors.allowed-origins"))
                .withMessageContaining("invalid origin");
    }

    @Test
    void rejectsOriginsWithPathQueryFragmentOrUserInfo() {
        List<String> invalid = List.of(
                "http://localhost:5500/",
                "http://localhost:5500/path",
                "http://localhost:5500?query=1",
                "http://localhost:5500#fragment",
                "http://user@localhost:5500");

        assertThat(invalid).allSatisfy(origin ->
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> AllowedOrigins.requireExplicit(
                                List.of(origin), "app.cors.allowed-origins"))
                        .withMessageContaining("invalid origin"));
    }

    @Test
    void rejectsNonHttpSchemesAndBlankEntries() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(
                        List.of("ws://localhost:5500"), "app.websocket.allowed-origins"))
                .withMessageContaining("invalid origin");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> AllowedOrigins.requireExplicit(
                        List.of("  "), "app.cors.allowed-origins"))
                .withMessageContaining("blank origins");
    }
}
