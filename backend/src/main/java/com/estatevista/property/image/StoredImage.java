package com.estatevista.property.image;

/** Server-owned external storage reference returned by an approved adapter. */
public record StoredImage(
        String storageProvider,
        String storagePublicId,
        String imageUrl) {

    public StoredImage {
        if (storageProvider == null || storageProvider.isBlank()
                || storageProvider.length() > 30) {
            throw new IllegalArgumentException("Invalid storage provider");
        }
        if (storagePublicId == null || storagePublicId.isBlank()
                || storagePublicId.length() > 255) {
            throw new IllegalArgumentException("Invalid storage public ID");
        }
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("Invalid image URL");
        }
    }
}
