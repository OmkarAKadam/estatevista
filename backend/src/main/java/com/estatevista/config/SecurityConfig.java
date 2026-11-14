package com.estatevista.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Baseline security shell.
 *
 * <p>Only the environment health endpoints and the error dispatch are public.
 * Every other request is denied until the authentication and business stages
 * add explicit matchers. No login, registration, logout, or authorization
 * workflow is implemented in this stage.</p>
 *
 * <p>The WebSocket handshake path is intentionally <strong>not</strong> public.
 * The transport is configured ({@link WebSocketConfig}), but the handshake stays
 * denied until the authentication stage adds session handshake authentication,
 * exact-Origin enforcement, and STOMP CONNECT/SUBSCRIBE/SEND authorization.</p>
 *
 * <p>Authentication and authorization failures use the same standard API error
 * envelope as controller exceptions. The access-denied handler is also used by
 * {@code CsrfFilter}, so tokenless mutating requests return the same JSON
 * shape.</p>
 *
 * <p><strong>SD-53 caveat:</strong> Spring Security's default CSRF
 * configuration is left enabled, but the approved session-bound synchronizer
 * design (256-bit token generator, {@code X-CSRF-TOKEN} response-header
 * delivery, raw-header-compatible request handler, post-authentication
 * rotation ordering, audit event, and Section 17 tests) is not implemented
 * here. Note that {@code csrf.spa()} installs a cookie-backed repository and
 * the default XOR handler expects a masked token; neither may be used as-is
 * with the approved session-backed raw-header flow. The authentication stage
 * must wire and test the design against the pinned Spring Security version.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeJsonError(request, response, ErrorCode.UNAUTHORIZED);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeJsonError(request, response, ErrorCode.FORBIDDEN);

        http
                .cors(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/actuator/health", "/error").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    private void writeJsonError(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        errorCode,
                        null,
                        List.of(),
                        request.getRequestURI()));
    }
}
