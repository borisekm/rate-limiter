package com.example.ratelimiter.core;

import com.example.ratelimiter.config.InfinispanConfig;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.VerboseResult;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.grid.infinispan.Bucket4jInfinispan;
import org.infinispan.Cache;
import org.infinispan.functional.FunctionalMap;
import org.infinispan.functional.FunctionalMap.ReadWriteMap;
import org.infinispan.manager.EmbeddedCacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket decisions, backed by Bucket4j over the distributed Infinispan cache.
 *
 * <p>Bucket4j owns the algorithm and the atomicity: its entry processor is applied on the key's
 * primary owner, so concurrent callers on any node are serialised per bucket without explicit
 * locking. Bucket state is stored as {@code byte[]}, which the cache store persists like any other
 * value.
 */
@Service
public class RateLimiter {

    private final ProxyManager<String> proxyManager;
    private final RateLimiterProperties props;
    private final Map<Resource, BucketConfiguration> configurations = new EnumMap<>(Resource.class);
    private final Clock clock;

    @Autowired
    public RateLimiter(EmbeddedCacheManager cacheManager, RateLimiterProperties props, Clock clock) {
        this(cacheManager.<String, byte[]>getCache(InfinispanConfig.BUCKETS_CACHE), props, clock);
    }

    RateLimiter(Cache<String, byte[]> buckets, RateLimiterProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        // Infinispan 16 made the functional-map implementation classes package-private; this is the
        // public route to the same read-write map Bucket4j's entry processor runs on.
        ReadWriteMap<String, byte[]> readWriteMap =
                FunctionalMap.create(buckets.getAdvancedCache()).toReadWriteMap();
        this.proxyManager = Bucket4jInfinispan.entryProcessorBasedBuilder(readWriteMap)
                .clientClock(wallClock())
                .build();
        props.resources().forEach((resource, policy) -> configurations.put(resource, bucketConfiguration(policy)));
    }

    /**
     * Wall-clock rather than Bucket4j's default {@code nanoTime}: buckets are persisted and read by
     * other nodes, and only wall-clock time means the same thing in a new JVM and on another host.
     */
    private TimeMeter wallClock() {
        return new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return TimeUnit.MILLISECONDS.toNanos(clock.millis());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    /**
     * {@code refillIntervally} is the stepwise refill this service is specified in: the whole
     * {@code refill-tokens} arrive at each period boundary rather than dripping continuously
     * ({@code refillGreedy}). A bucket starts full, which is Bucket4j's default initial tokens.
     */
    private static BucketConfiguration bucketConfiguration(ResourcePolicy policy) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(policy.capacity())
                        .refillIntervally(policy.refillTokens(), policy.refillPeriod())
                        .build())
                .build();
    }

    /**
     * Consumes one token from the {@code resource@identifier} bucket. The decision's
     * {@code retryAfterMillis} is the wait until the next refill that helps: when the call was
     * refused, until enough tokens are back; when it was allowed, until the next refill lands.
     */
    public Decision check(Resource resource, String identifier) {
        return check(resource, identifier, 1);
    }

    public Decision check(Resource resource, String identifier, long tokens) {
        ResourcePolicy policy = props.policyFor(resource);
        BucketProxy bucket = proxyManager.builder()
                .build(bucketName(resource, identifier), configurations.get(resource));

        // The verbose variant returns the resulting bucket state alongside the probe, which is what
        // lets an allowed call report its countdown without a second round trip to the owner.
        VerboseResult<ConsumptionProbe> verbose = bucket.asVerbose().tryConsumeAndReturnRemaining(tokens);
        ConsumptionProbe probe = verbose.getValue();

        long retryAfter;
        if (probe.isConsumed()) {
            // Time until one more token than we now hold - i.e. until the next refill. Asking for
            // remaining+1 is always within capacity here, because tokens were just consumed.
            long nowNanos = TimeUnit.MILLISECONDS.toNanos(clock.millis());
            long delayNanos = verbose.getState()
                    .calculateDelayNanosAfterWillBePossibleToConsume(probe.getRemainingTokens() + 1, nowNanos, false);
            retryAfter = toMillis(delayNanos);
        } else if (tokens > policy.capacity()) {
            retryAfter = Long.MAX_VALUE;   // more than the bucket can ever hold - waiting never helps
        } else {
            retryAfter = toMillis(probe.getNanosToWaitForRefill());
        }

        return new Decision(probe.isConsumed(), policy.capacity(), probe.getRemainingTokens(), retryAfter);
    }

    private static long toMillis(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        return Math.max(0, (nanos + 999_999L) / 1_000_000L);
    }

    /** Bucket naming scheme: {@code <resource>@<identifier>}. */
    static String bucketName(Resource resource, String identifier) {
        return resource.getValue() + "@" + identifier;
    }
}
