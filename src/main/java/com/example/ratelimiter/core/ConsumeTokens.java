package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;
import org.infinispan.util.function.SerializableBiFunction;

/**
 * The atomic refill-and-consume step. Executed via {@code cache.compute()} on the key's primary
 * owner, so concurrent callers on any node are serialized per key without explicit locking.
 * Marshallable (ProtoStream) so it can be shipped to a remote owner.
 *
 * <p>Refill is stepwise, matching how the quotas are specified: every {@code refillPeriodMillis} the
 * bucket gains {@code refillTokens}, capped at {@code capacity}. A bucket that has never been seen
 * starts full.
 *
 * <p>Times are epoch milliseconds, not {@code nanoTime()}: buckets outlive the JVM that wrote them
 * (see the cache store) and are read by other nodes, and only wall-clock time means the same thing
 * in all of those places.
 */
@Proto
public record ConsumeTokens(long tokens, long nowEpochMillis, long capacity, long refillTokens, long refillPeriodMillis)
        implements SerializableBiFunction<String, Bucket, Bucket> {

    @Override
    public Bucket apply(String key, Bucket existing) {
        long available;
        long anchor;
        if (existing == null || existing.refillAnchorEpochMillis() > nowEpochMillis) {
            // Unknown bucket, or the wall clock stepped back behind the anchor (an NTP correction).
            // Re-anchoring beats clamping the elapsed time to 0, which would stall refills for as
            // long as the jump lasted.
            available = existing == null ? capacity : Math.min(existing.tokens(), capacity);
            anchor = nowEpochMillis;
        } else {
            long elapsed = nowEpochMillis - existing.refillAnchorEpochMillis();
            long periods = elapsed / refillPeriodMillis;
            available = refill(existing.tokens(), periods);
            anchor = existing.refillAnchorEpochMillis() + periods * refillPeriodMillis;
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
