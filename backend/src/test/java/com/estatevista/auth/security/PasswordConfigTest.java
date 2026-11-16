package com.estatevista.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void encodesWithTheDelegatingBcryptPrefix() {
        String encoded = encoder.encode("Example-Password-123");

        assertThat(encoded).startsWith("{bcrypt}$2");
    }

    @Test
    void usesBcryptCostTwelve() {
        String encoded = encoder.encode("Example-Password-123");
        String bcryptHash = encoded.substring("{bcrypt}".length());

        assertThat(bcryptHash.split("\\$")[2]).isEqualTo("12");
    }

    @Test
    void producesADifferentSaltForEachPassword() {
        String first = encoder.encode("Example-Password-123");
        String second = encoder.encode("Example-Password-123");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void matchesOnlyTheOriginalPassword() {
        String encoded = encoder.encode("Example-Password-123");

        assertThat(encoder.matches("Example-Password-123", encoded)).isTrue();
        assertThat(encoder.matches("Example-Password-124", encoded)).isFalse();
        assertThat(encoded).isNotEqualTo("Example-Password-123");
    }
}
