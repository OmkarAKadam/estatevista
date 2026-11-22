package com.estatevista.property.image;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.cloudinary.Cloudinary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ImageStorage} adapter backed by the official Cloudinary
 * Java SDK.
 *
 * <p>The adapter accepts only the sanitized-byte DTO, composes every public ID
 * from a trusted property UUID and a server-generated UUID, uploads through the
 * backend with overwrite disabled, and returns only an HTTPS delivery URL.
 * Provider errors are reduced to a generic exception so the service can return
 * a safe 503 without leaking credentials or provider diagnostics.</p>
 */
public class CloudinaryImageStorage implements ImageStorage {

    static final String PROVIDER = "CLOUDINARY";

    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorage.class);
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp");
    private static final Pattern CLOUD_NAME = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern FOLDER_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String DELIVERY_HOST = "res.cloudinary.com";

    private final Cloudinary client;
    private final String cloudName;
    private final String folder;
    private final String publicIdPrefix;
    private final int timeoutSeconds;

    public CloudinaryImageStorage(CloudinaryProperties properties) {
        CloudinarySetup setup = setup(properties);
        this.client = setup.client();
        this.cloudName = validateCloudName(setup.cloudName());
        this.folder = normalizeFolder(setup.folder());
        this.publicIdPrefix = this.folder + "/";
        this.timeoutSeconds = setup.timeoutSeconds();
    }

    CloudinaryImageStorage(Cloudinary client, String cloudName, String folder) {
        this(client, cloudName, folder, 10);
    }

    private CloudinaryImageStorage(
            Cloudinary client, String cloudName, String folder, int timeoutSeconds) {
        this.client = Objects.requireNonNull(client, "Cloudinary client is required");
        this.cloudName = validateCloudName(cloudName);
        this.folder = normalizeFolder(folder);
        this.publicIdPrefix = this.folder + "/";
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public StoredImage upload(
            UUID propertyId, String storagePublicId, SanitizedImage image) {
        String expectedPublicId = buildPublicId(propertyId, storagePublicId);
        requireSanitized(image);

        Map<String, Object> options = new HashMap<>();
        options.put("public_id", expectedPublicId);
        options.put("resource_type", "image");
        options.put("type", "upload");
        options.put("secure", Boolean.TRUE);
        options.put("overwrite", Boolean.FALSE);
        options.put("use_filename", Boolean.FALSE);
        options.put("unique_filename", Boolean.FALSE);
        options.put("invalidate", Boolean.TRUE);
        options.put("filename", "image." + image.extension());
        addTimeoutOptions(options);

        Map<?, ?> response;
        try {
            response = client.uploader().upload(image.bytes(), options);
        } catch (IOException | RuntimeException exception) {
            log.warn("Cloudinary upload failed error={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("Cloudinary upload failed");
        }

        String returnedPublicId = stringValue(response, "public_id");
        if (!expectedPublicId.equals(returnedPublicId)) {
            throw new IllegalStateException("Cloudinary upload returned an unexpected identifier");
        }
        String imageUrl = stringValue(response, "secure_url");
        validateSecureUrl(imageUrl);
        return new StoredImage(PROVIDER, returnedPublicId, imageUrl);
    }

    @Override
    public void delete(String storageProvider, String storagePublicId) {
        if (!PROVIDER.equals(storageProvider)) {
            throw new IllegalStateException("Unsupported storage provider");
        }
        String publicId = requireManagedPublicId(storagePublicId);

        Map<String, Object> options = new HashMap<>();
        options.put("resource_type", "image");
        options.put("type", "upload");
        options.put("invalidate", Boolean.TRUE);
        addTimeoutOptions(options);

        Map<?, ?> response;
        try {
            response = client.uploader().destroy(publicId, options);
        } catch (IOException | RuntimeException exception) {
            log.warn("Cloudinary delete failed error={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("Cloudinary delete failed");
        }
        if (!"ok".equals(stringValue(response, "result"))) {
            throw new IllegalStateException("Cloudinary delete was not acknowledged");
        }
    }

    private static CloudinarySetup setup(CloudinaryProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("Cloudinary configuration is missing");
        }
        String cloudName = validateCloudName(properties.getCloudName());
        String apiKey = requireConfigValue(properties.getApiKey(), "api key", 128);
        String apiSecret = requireConfigValue(properties.getApiSecret(), "api secret", 512);
        String folder = normalizeFolder(properties.getFolder());
        int timeout = properties.getTimeoutSeconds();
        if (timeout < 1 || timeout > 60) {
            throw new IllegalStateException("Cloudinary configuration is invalid: timeout");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("cloud_name", cloudName);
        config.put("api_key", apiKey);
        config.put("api_secret", apiSecret);
        config.put("secure", Boolean.TRUE);
        config.put("timeout", timeout);
        config.put("analytics", Boolean.FALSE);

        Cloudinary client;
        try {
            client = new Cloudinary(config);
        } catch (RuntimeException exception) {
            log.warn("Cloudinary client initialization failed error={}",
                    exception.getClass().getSimpleName());
            throw new IllegalStateException("Cloudinary configuration is invalid");
        }
        return new CloudinarySetup(client, cloudName, folder, timeout);
    }

    private static void requireSanitized(SanitizedImage image) {
        if (image == null || image.bytes().length == 0
                || !ALLOWED_EXTENSIONS.contains(image.extension())
                || image.contentType() == null || !image.contentType().startsWith("image/")) {
            throw new IllegalStateException("Only sanitized image bytes may be uploaded");
        }
    }

    private String buildPublicId(UUID propertyId, String storagePublicId) {
        String propertyPart = requireUuid(String.valueOf(propertyId), "property identifier");
        String imagePart = requireUuid(storagePublicId, "storage identifier");
        return publicIdPrefix + propertyPart + "/" + imagePart;
    }

    private String requireManagedPublicId(String publicId) {
        if (publicId == null || !publicId.startsWith(publicIdPrefix)) {
            throw new IllegalStateException("Storage identifier is not managed by this adapter");
        }
        String remainder = publicId.substring(publicIdPrefix.length());
        String[] parts = remainder.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalStateException("Storage identifier is not managed by this adapter");
        }
        return publicIdPrefix
                + requireUuid(parts[0], "property identifier") + "/"
                + requireUuid(parts[1], "storage identifier");
    }

    private static String requireUuid(String value, String label) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException("Unsafe " + label);
        }
        return UUID.fromString(value).toString().toLowerCase(Locale.ROOT);
    }

    private static String validateCloudName(String value) {
        if (value == null || !CLOUD_NAME.matcher(value).matches()) {
            throw new IllegalStateException("Cloudinary configuration is invalid: cloud name");
        }
        return value;
    }

    private static String requireConfigValue(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("Cloudinary configuration is invalid: " + label);
        }
        return value;
    }

    private static String normalizeFolder(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Cloudinary configuration is invalid: folder");
        }
        String candidate = value.trim();
        if (candidate.startsWith("/") || candidate.endsWith("/") || candidate.contains("//")) {
            throw new IllegalStateException("Cloudinary configuration is invalid: folder");
        }
        String[] segments = candidate.split("/", -1);
        if (segments.length == 0 || segments.length > 8) {
            throw new IllegalStateException("Cloudinary configuration is invalid: folder");
        }
        for (String segment : segments) {
            if (!FOLDER_SEGMENT.matcher(segment).matches()) {
                throw new IllegalStateException("Cloudinary configuration is invalid: folder");
            }
        }
        return candidate;
    }

    private void validateSecureUrl(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cloudinary returned an unsafe delivery URL");
        }
        String path = uri.getPath();
        String expectedPrefix = "/" + cloudName + "/image/upload/";
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !DELIVERY_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || path == null || !path.startsWith(expectedPrefix)
                || path.contains("..") || path.contains("\\")) {
            throw new IllegalStateException("Cloudinary returned an unsafe delivery URL");
        }
    }

    private void addTimeoutOptions(Map<String, Object> options) {
        // cloudinary-http5 reads these from each request's options map.
        options.put("timeout", timeoutSeconds);
        options.put("connect_timeout", timeoutSeconds);
        options.put("connection_request_timeout", timeoutSeconds);
    }

    private static String stringValue(Map<?, ?> response, String key) {
        if (response == null) {
            return null;
        }
        Object value = response.get(key);
        return value instanceof String string ? string : null;
    }

    private record CloudinarySetup(
            Cloudinary client, String cloudName, String folder, int timeoutSeconds) {
    }
}
