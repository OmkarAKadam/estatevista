package com.estatevista;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * EstateVista V2 backend entry point.
 *
 * <p>This stage establishes the application shell only. Authentication,
 * authorization, business modules, and WebSocket message handling are
 * implemented in later stages.</p>
 *
 * <p>Boot's default in-memory user is excluded so the shell never starts with
 * generated credentials. The authentication stage will provide a real
 * {@code UserDetailsService}.</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class EstateVistaApplication {

    public static void main(String[] args) {
        SpringApplication.run(EstateVistaApplication.class, args);
    }
}
