package com.estatevista.property.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment-backed configuration for the optional production Cloudinary
 * adapter. Credentials are bound from environment variables through
 * {@code application.yml}; no secret has a usable default.
 */
@ConfigurationProperties(prefix = "app.storage.cloudinary")
public class CloudinaryProperties {

    private boolean enabled;
    private String cloudName;
    private String apiKey;
    private String apiSecret;
    private String folder = "estatevista/properties";
    private int timeoutSeconds = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
