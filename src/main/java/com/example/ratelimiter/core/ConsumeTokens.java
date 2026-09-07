package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;
import org.infinispan.util.function.SerializableBiFunction;

/**
 * The atomic refill-and-consume step. Executed via {@code cache.compute()} on the key's primary
 * owner, so concurrent callers on any node are serialized per key without explicit locking.
 * Marshallable (ProtoStream) so it can be shipped to a remote owner.
 */
@Proto
public record ConsumeTokens(int tokens, long nowNanos, long capacity, double refillPerSecond)
        implements SerializableBiFunction<String, Bucket, Bucket> {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    @Override
    public Bucket apply(String key, Bucket existing) {
        double available;
        if (existing == null) {
            available = capacity;
        } else {
            long elapsed = Math.max(0, nowNanos - existing.lastRefillNanos());
            double refilled = elapsed / NANOS_PER_SECOND * refillPerSecond;
            available = Math.min(capacity, existing.tokens() + refilled);
        }

        if (available >= tokens) {
            return new Bucket(available - tokens, nowNanos, true);
        }
        return new Bucket(available, nowNanos, false);
    }
}
