package com.estatevista.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Configuration contract tests.
 *
 * <p>These assert the approved shared and production settings that are easy to
 * weaken accidentally: Flyway ownership of the schema, Hibernate validation,
 * restricted Actuator exposure, secure production cookies, required production
 * properties without fallbacks, and production SQL/bind logging suppression.</p>
 */
class ApplicationConfigurationTest {

    private final PropertySource<?> shared = load("application.yml");
    private final PropertySource<?> production = load("application-prod.yml");

    @Test
    void flywayOwnsTheSchemaAndHibernateOnlyValidatesIt() {
        assertThat(shared.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(shared.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(shared.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(shared.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
        assertThat(shared.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
    }

    @Test
    void actuatorExposesOnlyHealthWithoutDetails() {
        assertThat(shared.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(shared.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void developmentSessionCookieDefaultsStayHttpOnlyAndSameSiteLax() {
        assertThat(shared.getProperty("server.servlet.session.cookie.name"))
                .isEqualTo("EV_SESSION");
        assertThat(shared.getProperty("server.servlet.session.cookie.http-only")).isEqualTo(true);
        assertThat(shared.getProperty("server.servlet.session.cookie.same-site"))
                .isEqualTo("lax");
        assertThat(shared.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("${SESSION_COOKIE_SECURE:false}");
    }

    @Test
    void productionRequiresDatabaseAndOriginsWithoutFallbacks() {
        assertThat(production.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(production.getProperty("spring.datasource.username"))
                .isEqualTo("${DB_USERNAME}");
        assertThat(production.getProperty("spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");
        assertThat(production.getProperty("app.cors.allowed-origins"))
                .isEqualTo("${APP_CORS_ALLOWED_ORIGINS}");
        assertThat(production.getProperty("app.websocket.allowed-origins"))
                .isEqualTo("${APP_WEBSOCKET_ALLOWED_ORIGINS}");
    }

    @Test
    void productionForcesSecureCookiesAndNativeForwardedHeaders() {
        assertThat(production.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo(true);
        assertThat(production.getProperty("server.forward-headers-strategy"))
                .isEqualTo("native");
    }

    @Test
    void productionSuppressesSqlBindAndExtractionLogging() {
        assertThat(production.getProperty("spring.jpa.show-sql")).isEqualTo(false);
        assertThat(production.getProperty("logging.level.org.hibernate.SQL"))
                .isEqualTo("WARN");
        assertThat(production.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
                .isEqualTo("WARN");
        assertThat(production.getProperty("logging.level.org.hibernate.orm.jdbc.extract"))
                .isEqualTo("WARN");
    }

    private static PropertySource<?> load(String resourceName) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load(resourceName, new ClassPathResource(resourceName));
            assertThat(sources).hasSize(1);
            return sources.getFirst();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load " + resourceName, exception);
        }
    }
}
