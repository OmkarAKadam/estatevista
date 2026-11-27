package com.estatevista.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 3.6 shared rate-limit thresholds (OD-6).
 *
 * <p>Defaults follow the approved values in SECURITY_ARCHITECTURE.md §11.3 and
 * §11.9: Stage 3 authentication limits plus the Stage 4 property baselines
 * (public search 120/minute/IP, detail 300/minute/IP, authenticated API
 * 300/minute/user, admin mutations 60/minute/admin, image uploads
 * 20/hour/user). Values are externalised so deployments can tune them only
 * through a documented contract revision. Attempt counters live in PostgreSQL,
 * not in application memory.</p>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Login login = new Login();
    private Registration registration = new Registration();
    private ImageUpload imageUpload = new ImageUpload();
    private Limit publicSearch = new Limit(120, 60);
    private Limit propertyDetail = new Limit(300, 60);
    private Limit authenticatedApi = new Limit(300, 60);
    private Limit adminMutation = new Limit(60, 60);
    private Limit favoriteMutation = new Limit(60, 60);
    private Limit inquirySubmissionBurst = new Limit(5, 60);
    private Limit inquirySubmissionSustained = new Limit(30, 3600);
    private Limit wsConnectionAttempt = new Limit(20, 60);
    private Limit wsMessageUser = new Limit(30, 60);
    private Limit wsMessageConversation = new Limit(10, 60);

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

    public ImageUpload getImageUpload() {
        return imageUpload;
    }

    public void setImageUpload(ImageUpload imageUpload) {
        this.imageUpload = imageUpload;
    }

    public Limit getPublicSearch() {
        return publicSearch;
    }

    public void setPublicSearch(Limit publicSearch) {
        this.publicSearch = publicSearch;
    }

    public Limit getPropertyDetail() {
        return propertyDetail;
    }

    public void setPropertyDetail(Limit propertyDetail) {
        this.propertyDetail = propertyDetail;
    }

    public Limit getAuthenticatedApi() {
        return authenticatedApi;
    }

    public void setAuthenticatedApi(Limit authenticatedApi) {
        this.authenticatedApi = authenticatedApi;
    }

    public Limit getAdminMutation() {
        return adminMutation;
    }

    public void setAdminMutation(Limit adminMutation) {
        this.adminMutation = adminMutation;
    }

    public Limit getFavoriteMutation() {
        return favoriteMutation;
    }

    public void setFavoriteMutation(Limit favoriteMutation) {
        this.favoriteMutation = favoriteMutation;
    }

    public Limit getInquirySubmissionBurst() {
        return inquirySubmissionBurst;
    }

    public void setInquirySubmissionBurst(Limit inquirySubmissionBurst) {
        this.inquirySubmissionBurst = inquirySubmissionBurst;
    }

    public Limit getInquirySubmissionSustained() {
        return inquirySubmissionSustained;
    }

    public void setInquirySubmissionSustained(Limit inquirySubmissionSustained) {
        this.inquirySubmissionSustained = inquirySubmissionSustained;
    }

    public Limit getWsConnectionAttempt() {
        return wsConnectionAttempt;
    }

    public void setWsConnectionAttempt(Limit wsConnectionAttempt) {
        this.wsConnectionAttempt = wsConnectionAttempt;
    }

    public Limit getWsMessageUser() {
        return wsMessageUser;
    }

    public void setWsMessageUser(Limit wsMessageUser) {
        this.wsMessageUser = wsMessageUser;
    }

    public Limit getWsMessageConversation() {
        return wsMessageConversation;
    }

    public void setWsMessageConversation(Limit wsMessageConversation) {
        this.wsMessageConversation = wsMessageConversation;
    }

    /** Shared threshold/window holder for approved Stage 4 rate-limit scopes. */
    public static class Limit {
        private int maxAttempts;
        private int windowSeconds;

        public Limit() {
        }

        public Limit(int maxAttempts, int windowSeconds) {
            this.maxAttempts = maxAttempts;
            this.windowSeconds = windowSeconds;
        }

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

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Approved property-image upload threshold for one authenticated actor. */
    public static class ImageUpload {
        private int maxAttempts = 20;
        private int windowSeconds = 3600;

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
}
