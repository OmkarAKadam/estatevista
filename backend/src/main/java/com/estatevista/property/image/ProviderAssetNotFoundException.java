package com.estatevista.property.image;

/**
 * Raised by an {@link ImageStorage} adapter when the provider confirms that an
 * asset is already absent. Cleanup treats this as success so retries are
 * idempotent.
 */
public class ProviderAssetNotFoundException extends RuntimeException {

    public ProviderAssetNotFoundException(String message) {
        super(message);
    }
}
