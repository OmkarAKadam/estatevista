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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security filter chain.
 *
 * <p>The chain is deny-by-default. Stage 3.4 adds session-based authentication
 * endpoints: CSRF bootstrap and login are publicly reachable (login remains CSRF
 * protected), the authenticated principal can be inspected at {@code /auth/me},
 * and logout invalidates the server-side session. Registration remains
 * CSRF-exempt with exact Origin/Referer validation. The WebSocket handshake path
 * remains intentionally denied.</p>
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
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository) throws Exception {
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

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(registrationPost))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(registrationPost).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
                        .requestMatchers("/api/v1/health", "/actuator/health", "/error").permitAll()
                        .anyRequest().denyAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation().migrateSession())
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
