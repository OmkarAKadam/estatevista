package com.estatevista.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 3.6 shared rate-limit thresholds (OD-6).
 *
 * <p>Defaults follow the approved values in SECURITY_ARCHITECTURE.md §11.3:
 * ten failed logins per account within fifteen minutes and five registrations
 * per client address per minute. Values are externalised so deployments can
 * tune them without code changes. Attempt counters live in PostgreSQL, not in
 * application memory.</p>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Login login = new Login();
    private Registration registration = new Registration();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    /** Failed-login threshold for one normalized account/client pair. */
    public static class Login {
        private int maxAttempts = 10;
        private int windowSeconds = 900;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Registration threshold for one client address. */
    public static class Registration {
        private int maxAttempts = 5;
        private int windowSeconds = 60;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }
    }
}
