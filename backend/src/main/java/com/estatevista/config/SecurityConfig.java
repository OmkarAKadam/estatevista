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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security filter chain.
 *
 * <p>The chain is deny-by-default. Stage 3.2 opens exactly two additions:</p>
 * <ul>
 *   <li>{@code POST /api/v1/auth/register} for visitor registration, CSRF-exempt
 *       because no session exists yet, with exact Origin/Referer validation; and</li>
 *   <li>{@code GET|PATCH /api/v1/users/me} for the authenticated principal's own
 *       account. The PATCH keeps the default session-bound CSRF requirement.</li>
 * </ul>
 * <p>Every other request remains denied. Login, logout, the CSRF bootstrap, and
 * the authorization model for business endpoints are later sub-stages.</p>
 *
 * <p>The WebSocket handshake path is still intentionally denied.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public SecurityConfig(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeJsonError(request, response, ErrorCode.UNAUTHORIZED, null);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeJsonError(request, response, ErrorCode.FORBIDDEN, null);

        PathPatternRequestMatcher registrationPost = PathPatternRequestMatcher.pathPattern(
                HttpMethod.POST, "/api/v1/auth/register");

        List<String> allowedOrigins = AllowedOrigins.requireExplicit(
                appProperties.cors().allowedOrigins(), "app.cors.allowed-origins");
        AuthOriginValidationFilter originFilter = new AuthOriginValidationFilter(
                allowedOrigins, objectMapper, registrationPost);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers(registrationPost))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(registrationPost).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
                        .requestMatchers("/api/v1/health", "/actuator/health", "/error").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(originFilter, CsrfFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    private void writeJsonError(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode,
            String message) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        errorCode,
                        message,
                        List.of(),
                        request.getRequestURI()));
    }
}
