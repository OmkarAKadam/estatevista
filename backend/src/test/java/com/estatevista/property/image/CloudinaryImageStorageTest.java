package com.estatevista.property.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Deterministic adapter tests; no Cloudinary network call is made. */
class CloudinaryImageStorageTest {

    private static final String CLOUD = "estatevista-test";
    private static final String FOLDER = "estatevista/properties";
    private static final String PUBLIC_ID_PREFIX = FOLDER + "/";

    private Cloudinary cloudinary;
    private Uploader uploader;
    private CloudinaryImageStorage storage;

    @BeforeEach
    void setUp() {
        cloudinary = mock(Cloudinary.class);
        uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        storage = new CloudinaryImageStorage(cloudinary, CLOUD, FOLDER);
    }

    @Test
    void uploadUsesTrustedIdentifierAndSafeOptions() throws Exception {
        UUID propertyId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        String publicId = expectedPublicId(propertyId, imageId);
        byte[] bytes = {1, 2, 3, 4};
        SanitizedImage image = new SanitizedImage(bytes, "png", "image/png", 240, 220);
        when(uploader.upload(any(), any())).thenReturn(uploadResponse(publicId,
                "https://res.cloudinary.com/" + CLOUD + "/image/upload/v1/" + publicId + ".png"));

        StoredImage stored = storage.upload(propertyId, imageId.toString(), image);

        assertThat(stored.storageProvider()).isEqualTo("CLOUDINARY");
        assertThat(stored.storagePublicId()).isEqualTo(publicId);
        assertThat(stored.imageUrl()).isEqualTo(
                "https://res.cloudinary.com/" + CLOUD + "/image/upload/v1/" + publicId + ".png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(same(bytes), options.capture());
        assertThat(options.getValue())
                .containsEntry("public_id", publicId)
                .containsEntry("resource_type", "image")
                .containsEntry("type", "upload")
                .containsEntry("secure", Boolean.TRUE)
                .containsEntry("overwrite", Boolean.FALSE)
                .containsEntry("use_filename", Boolean.FALSE)
                .containsEntry("unique_filename", Boolean.FALSE)
                .containsEntry("invalidate", Boolean.TRUE)
                .containsEntry("filename", "image.png")
                .containsEntry("timeout", 10)
                .containsEntry("connect_timeout", 10)
                .containsEntry("connection_request_timeout", 10);
    }

    @Test
    void deleteUsesManagedIdentifierAndAcknowledgement() throws Exception {
        String publicId = expectedPublicId(UUID.randomUUID(), UUID.randomUUID());
        when(uploader.destroy(eq(publicId), any())).thenReturn(Map.of("result", "ok"));

        storage.delete("CLOUDINARY", publicId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).destroy(eq(publicId), options.capture());
        assertThat(options.getValue())
                .containsEntry("resource_type", "image")
                .containsEntry("type", "upload")
                .containsEntry("invalidate", Boolean.TRUE)
                .containsEntry("timeout", 10);
    }

    @Test
    void missingCredentialsFailBeforeAnyProviderCall() {
        CloudinaryProperties properties = validProperties();
        properties.setApiSecret("   ");

        assertThatThrownBy(() -> new CloudinaryImageStorage(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api secret");
    }

    @Test
    void invalidProviderConfigurationIsRejected() {
        CloudinaryProperties badFolder = validProperties();
        badFolder.setFolder("../escape");

        CloudinaryProperties badTimeout = validProperties();
        badTimeout.setTimeoutSeconds(0);

        CloudinaryProperties badCloud = validProperties();
        badCloud.setCloudName("bad/name");

        assertThatThrownBy(() -> new CloudinaryImageStorage(badFolder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("folder");
        assertThatThrownBy(() -> new CloudinaryImageStorage(badTimeout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> new CloudinaryImageStorage(badCloud))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloud name");
    }

    @Test
    void providerFailuresAndTimeoutsAreReducedToSafeErrors() throws Exception {
        SanitizedImage image = sanitizedImage();
        UUID propertyId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();

        when(uploader.upload(any(), any()))
                .thenThrow(new IOException("api_key=SECRET and api_secret=SECRET"))
                .thenThrow(new SocketTimeoutException("timed out api_secret=SECRET"));

        assertThatThrownBy(() -> storage.upload(propertyId, imageId.toString(), image))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloudinary upload failed");
        assertThatThrownBy(() -> storage.upload(propertyId, imageId.toString(), image))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloudinary upload failed");
    }

    @Test
    void unsafeStorageIdentifiersAreRejectedBeforeProviderCall() {
        SanitizedImage image = sanitizedImage();
        UUID propertyId = UUID.randomUUID();

        assertThatThrownBy(() -> storage.upload(
                propertyId, "../../evil.png", image))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.delete("CLOUDINARY", "../outside"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.delete("OTHER_PROVIDER",
                expectedPublicId(propertyId, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(uploader);
    }

    @Test
    void unexpectedProviderIdentifierAndUnsafeUrlAreRejected() throws Exception {
        UUID propertyId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        String expected = expectedPublicId(propertyId, imageId);
        SanitizedImage image = sanitizedImage();

        when(uploader.upload(any(), any()))
                .thenReturn(uploadResponse(expected + "/other", expected + ".png"))
                .thenReturn(uploadResponse(expected,
                        "http://res.cloudinary.com/" + CLOUD + "/image/upload/v1/" + expected + ".png"))
                .thenReturn(uploadResponse(expected,
                        "https://res.cloudinary.com.evil.test/" + CLOUD + "/image/upload/v1/" + expected + ".png"));

        assertThatThrownBy(() -> storage.upload(propertyId, imageId.toString(), image))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.upload(propertyId, imageId.toString(), image))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.upload(propertyId, imageId.toString(), image))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unacknowledgedDeleteIsTreatedAsFailure() throws Exception {
        String publicId = expectedPublicId(UUID.randomUUID(), UUID.randomUUID());
        when(uploader.destroy(eq(publicId), any())).thenReturn(Map.of("result", "not found"));

        assertThatThrownBy(() -> storage.delete("CLOUDINARY", publicId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloudinary delete was not acknowledged");
    }

    private static CloudinaryProperties validProperties() {
        CloudinaryProperties properties = new CloudinaryProperties();
        properties.setEnabled(true);
        properties.setCloudName(CLOUD);
        properties.setApiKey("123456789012345");
        properties.setApiSecret("unit-test-secret");
        properties.setFolder(FOLDER);
        properties.setTimeoutSeconds(10);
        return properties;
    }

    private static SanitizedImage sanitizedImage() {
        return new SanitizedImage(new byte[] {1, 2, 3}, "png", "image/png", 240, 240);
    }

    private static String expectedPublicId(UUID propertyId, UUID imageId) {
        return PUBLIC_ID_PREFIX + propertyId + "/" + imageId;
    }

    private static Map<String, Object> uploadResponse(String publicId, String secureUrl) {
        Map<String, Object> response = new HashMap<>();
        response.put("public_id", publicId);
        response.put("secure_url", secureUrl);
        return response;
    }
}
