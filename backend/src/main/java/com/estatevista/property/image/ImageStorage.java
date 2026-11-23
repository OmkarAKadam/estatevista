package com.estatevista.property.image;

import java.util.UUID;

/**
 * Backend-only external image storage boundary.
 *
 * <p>No browser-facing implementation exists. The production adapter is the
 * backend Cloudinary SDK implementation and is registered only when explicitly
 * enabled with environment-provided credentials. Without an adapter the image
 * service fails closed.</p>
 */
public interface ImageStorage {

    /**
     * Stores sanitized bytes under a server-generated key and returns only safe
     * metadata. The caller never supplies a filename or client-selected path.
     */
    StoredImage upload(
            UUID propertyId,
            String storagePublicId,
            SanitizedImage image);

    /**
     * Removes a stored object by its server-owned identifier. Implementations
     * must throw on failure so the caller never reports a silent success.
     */
    void delete(String storageProvider, String storagePublicId);
}
