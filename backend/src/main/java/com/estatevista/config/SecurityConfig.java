package com.estatevista.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.SessionAuthorizationFilter;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse;
import com.estatevista.security.PropertyRateLimitFilter;
import com.estatevista.security.RateLimitService;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.CorsFilter;

/**
 * Security filter chain.
 *
 * <p>The chain is deny-by-default. Stage 3.4 adds session-based authentication
 * endpoints: CSRF bootstrap and login are publicly reachable (login remains CSRF
 * protected), the authenticated principal can be inspected at {@code /auth/me},
 * and logout invalidates the server-side session. Registration remains
 * CSRF-exempt with exact Origin/Referer validation. Stage 3.5 requires an
 * authenticated session for the WebSocket handshake and restricts the management
 * path prefixes by role. Everything not explicitly allowlisted stays denied,
 * including non-health Actuator endpoints and non-GET health probes. Stage 3.6
 * corrective audit coverage records CSRF failures and authorization denials in
 * this handler without changing the generic error response.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final ObjectProvider<AccountAuthorizationService> accountAuthorizationService;
    private final ObjectProvider<AuditEventService> auditEventService;
    private final ObjectProvider<RateLimitService> rateLimitService;

    public SecurityConfig(
            ObjectMapper objectMapper,
            AppProperties appProperties,
            ObjectProvider<AccountAuthorizationService> accountAuthorizationService,
            ObjectProvider<AuditEventService> auditEventService,
            ObjectProvider<RateLimitService> rateLimitService) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.accountAuthorizationService = accountAuthorizationService;
        this.auditEventService = auditEventService;
        this.rateLimitService = rateLimitService;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new SecureCsrfTokenRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeJsonError(request, response, ErrorCode.UNAUTHORIZED, null);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> {
            AuditEventService audit = auditEventService.getIfAvailable();
            if (audit != null) {
                if (exception instanceof CsrfException) {
                    audit.recordCsrfFailure(request);
                } else {
                    audit.recordAccessDenied(request);
                }
            }
            writeJsonError(request, response, ErrorCode.FORBIDDEN, null);
        };

        PathPatternRequestMatcher registrationPost = PathPatternRequestMatcher.pathPattern(
                HttpMethod.POST, "/api/v1/auth/register");

        List<String> allowedOrigins = AllowedOrigins.requireExplicit(
                appProperties.cors().allowedOrigins(), "app.cors.allowed-origins");
        RequestMatcher stateChangingApiRequest = request -> {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
            return path.startsWith("/api/");
        };
        AuthOriginValidationFilter originFilter = new AuthOriginValidationFilter(
                allowedOrigins, objectMapper, stateChangingApiRequest, auditEventService);

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(registrationPost))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'self'; object-src 'none'; "
                                        + "frame-ancestors 'none'; form-action 'self'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000L))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicy(policy -> policy.policy(
                                "camera=(), microphone=(), geolocation=()")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(registrationPost).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
                        // Stage 6.2 favorites: explicit BUYER-only surfaces. The
                        // literal batch status route precedes the variable one.
                        .requestMatchers(HttpMethod.GET, "/api/v1/favorites").hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/favorites/status")
                                .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/favorites/{propertyId}/status")
                                .hasRole("BUYER")
                        .requestMatchers(
                                HttpMethod.POST, "/api/v1/favorites/{propertyId}")
                                .hasRole("BUYER")
                        .requestMatchers(
                                HttpMethod.DELETE, "/api/v1/favorites/{propertyId}")
                                .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, appProperties.websocket().endpoint()).authenticated()
                        // Stage 4.3: method-specific assignment route must be
                        // evaluated before the broader admin prefix matcher.
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/admin/properties/{propertyId}/assignment").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/agents/me/**").hasRole("AGENT")
                        // Stage 4.2: explicit property routes. Unlisted property
                        // subpaths remain denied by anyRequest().denyAll().
                        .requestMatchers(HttpMethod.POST, "/api/v1/properties").hasRole("ADMIN")
                        // Deferred routes must not be mistaken for a UUID path variable.
                        .requestMatchers(HttpMethod.GET, "/api/v1/properties/featured").denyAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/properties",
                                "/api/v1/properties/recent",
                                "/api/v1/properties/{propertyId}",
                                "/api/v1/properties/{propertyId}/related").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/properties").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/agents/me/properties").hasRole("AGENT")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/properties/{propertyId}/status").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/properties/{propertyId}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/properties/{propertyId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/properties/{propertyId}").hasRole("ADMIN")
                        // Stage 4.5: image metadata reads are public only for
                        // published properties; mutations require a session and
                        // are authorized again in the service layer.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/properties/{propertyId}/images").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/properties/{propertyId}/images").authenticated()
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/properties/{propertyId}/images/{imageId}",
                                "/api/v1/properties/{propertyId}/images/{imageId}/primary")
                                .authenticated()
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/v1/properties/{propertyId}/images/{imageId}")
                                .authenticated()
                        // Stage 4.4: public, read-only reference-data lookups.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/property-categories",
                                "/api/v1/property-subtypes",
                                "/api/v1/amenities").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository));

        // The resolver is absent only in isolated web-slice tests that import
        // this filter chain without persistence. Full application contexts
        // always install it and therefore re-verify session authorities.
        AccountAuthorizationService resolver = accountAuthorizationService.getIfAvailable();
        if (resolver != null) {
            http.addFilterAfter(
                    new SessionAuthorizationFilter(resolver, securityContextRepository),
                    SecurityContextHolderFilter.class);
        }

        RateLimitService limiter = rateLimitService.getIfAvailable();
        if (limiter != null) {
            http.addFilterBefore(
                    new PropertyRateLimitFilter(limiter, objectMapper),
                    AuthorizationFilter.class);
        }

        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation().migrateSession())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(originFilter, CorsFilter.class)
                .addFilterAfter(new PrivateResponseNoStoreFilter(), CorsFilter.class)
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
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        errorCode,
                        message,
                        List.of(),
                        request.getRequestURI()));
    }
}
