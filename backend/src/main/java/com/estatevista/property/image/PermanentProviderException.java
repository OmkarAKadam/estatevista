package com.estatevista.property.image;

/**
 * Raised when a provider call fails for a reason that retrying cannot fix, such
 * as an unsupported provider or a storage identifier that is not managed by the
 * adapter. Cleanup dead-letters these tasks immediately instead of retrying an
 * invalid request. It remains an {@link IllegalStateException} so the existing
 * safe adapter contract and its deterministic tests are preserved.
 */
public class PermanentProviderException extends IllegalStateException {

    public PermanentProviderException(String message) {
        super(message);
    }
}
