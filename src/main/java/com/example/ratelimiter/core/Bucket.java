package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;

/**
 * Token-bucket state stored in Infinispan.
 *
 * @param tokens          fractional tokens currently available
 * @param lastRefillNanos monotonic timestamp of the last refill
 * @param lastAllowed     outcome of the most recent consume attempt (lets compute() return the decision)
 */
@Proto
public record Bucket(double tokens, long lastRefillNanos, boolean lastAllowed) {
}
