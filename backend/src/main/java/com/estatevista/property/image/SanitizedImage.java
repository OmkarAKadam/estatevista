package com.estatevista.property.image;

/**
 * Canonical, metadata-free bytes ready for the external storage adapter.
 *
 * @param bytes       freshly re-encoded image bytes with no source metadata
 * @param extension   trusted lowercase extension derived from actual content
 * @param contentType trusted MIME type derived from actual content
 * @param width       decoded pixel width
 * @param height      decoded pixel height
 */
public record SanitizedImage(
        byte[] bytes,
        String extension,
        String contentType,
        int width,
        int height) {

    public SanitizedImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Sanitized image bytes are required");
        }
    }
}
