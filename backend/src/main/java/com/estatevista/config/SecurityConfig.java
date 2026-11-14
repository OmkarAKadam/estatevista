package com.estatevista.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Baseline security shell.
 *
 * <p>Only the environment health endpoints and the WebSocket handshake path are
 * public. Every other request is denied until the authentication and business
 * stages add explicit matchers. No login, registration, logout, or
 * authorization workflow is implemented in this stage.</p>
 *
 * <p><strong>SD-53 caveat:</strong> Spring Security's default CSRF
 * configuration is left enabled, but the approved session-bound synchronizer
 * design (256-bit token generator, {@code X-CSRF-TOKEN} response-header
 * delivery, raw-header-compatible request handler, post-authentication
 * rotation ordering, audit event, and Section 17 tests) is not implemented
 * here. The {@code accessDeniedHandler} below already produces the JSON
 * {@code 403} shape for CSRF failures. Note that {@code csrf.spa()} installs a
 * cookie-backed repository and the default XOR handler expects a masked token;
 * neither may be used as-is with the approved session-backed raw-header flow.
 * The authentication stage must wire and test the design against the pinned
 * Spring Security version.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/actuator/health", "/error").permitAll()
                        // Setup-only: the STOMP handshake is not authenticated yet.
                        // The authentication stage must add the session handshake
                        // interceptor and exact Origin enforcement before exposure.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN, "FORBIDDEN")));
        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
