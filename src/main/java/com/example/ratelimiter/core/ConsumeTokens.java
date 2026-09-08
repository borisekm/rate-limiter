package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;
import org.infinispan.util.function.SerializableBiFunction;

/**
 * The atomic refill-and-consume step. Executed via {@code cache.compute()} on the key's primary
 * owner, so concurrent callers on any node are serialized per key without explicit locking.
 * Marshallable (ProtoStream) so it can be shipped to a remote owner.
 *
 * <p>Refill is stepwise, matching how the quotas are specified: every {@code refillPeriodNanos} the
 * bucket gains {@code refillTokens}, capped at {@code capacity}. A bucket that has never been seen
 * starts full.
 */
@Proto
public record ConsumeTokens(long tokens, long nowNanos, long capacity, long refillTokens, long refillPeriodNanos)
        implements SerializableBiFunction<String, Bucket, Bucket> {

    @Override
    public Bucket apply(String key, Bucket existing) {
        long available;
        long anchor;
        if (existing == null) {
            available = capacity;
            anchor = nowNanos;
        } else {
            long elapsed = Math.max(0, nowNanos - existing.refillAnchorNanos());
            long periods = elapsed / refillPeriodNanos;
            available = refill(existing.tokens(), periods);
            anchor = existing.refillAnchorNanos() + periods * refillPeriodNanos;
        }

        if (available >= tokens) {
            return new Bucket(available - tokens, anchor, true);
        }
        return new Bucket(available, anchor, false);
    }

    /** Tokens after {@code periods} refill steps, saturating at capacity without overflowing. */
    private long refill(long current, long periods) {
        long missing = capacity - current;
        if (missing <= 0 || periods <= 0) {
            return Math.min(current, capacity);
        }
        long periodsToFull = ceilDiv(missing, refillTokens);
        return periods >= periodsToFull ? capacity : current + periods * refillTokens;
    }

    static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
