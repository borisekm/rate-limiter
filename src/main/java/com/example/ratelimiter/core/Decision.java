package com.example.ratelimiter.core;

/**
 * @param degraded the bucket store could not be consulted and this is the configured fallback
 *                 ({@code ratelimiter.when-store-unavailable}), not a real count
 */
public record Decision(boolean allowed, long limit, long remaining, long retryAfterMillis, boolean degraded) {

    public Decision(boolean allowed, long limit, long remaining, long retryAfterMillis) {
        this(allowed, limit, remaining, retryAfterMillis, false);
    }
}
