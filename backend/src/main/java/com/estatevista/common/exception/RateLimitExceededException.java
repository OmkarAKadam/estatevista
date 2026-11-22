package com.estatevista.common.exception;

/**
 * Rate-limit rejection carrying the approved {@code Retry-After} value.
 *
 * <p>The shared limiter throws this subtype so both controller exceptions and
 * security-filter rejections can emit the same client-safe envelope and header.
 * No raw client identity is included in the exception.</p>
 */
public final class RateLimitExceededException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many requests. Please try again later.");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
