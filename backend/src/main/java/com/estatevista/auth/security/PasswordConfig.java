package com.estatevista.auth.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Approved password encoder configuration (SD-09, SECURITY_ARCHITECTURE.md §9.1).
 *
 * <p>BCrypt with cost factor 12 is wrapped in a {@link DelegatingPasswordEncoder}
 * so new hashes carry the {@code {bcrypt}} prefix and a future algorithm change
 * can support rehash-on-login without mixing formats silently.</p>
 *
 * <p>Argon2id is not used because no BouncyCastle dependency is present; adding
 * one is a separate approved dependency decision.</p>
 */
@Configuration
public class PasswordConfig {

    private static final String DEFAULT_ENCODER_ID = "bcrypt";
    private static final int BCRYPT_COST = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
                DEFAULT_ENCODER_ID,
                new BCryptPasswordEncoder(BCRYPT_COST));
        return new DelegatingPasswordEncoder(DEFAULT_ENCODER_ID, encoders);
    }
}
