package com.example.ratelimiter.core;

import com.example.ratelimiter.config.HotRodConfig;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import com.example.ratelimiter.config.RateLimiterProperties.WhenStoreUnavailable;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.VerboseResult;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Token-bucket decisions, backed by Bucket4j over a remote Infinispan cache.
 *
 * <p>Bucket4j owns the algorithm; atomicity comes from versioned writes in {@link HotRodProxyManager},
 * so concurrent callers on any instance are serialised per bucket without a lock. Bucket state is
 * stored as Bucket4j's own {@code byte[]}.
 *
 * <p>When the server cannot be reached, a check answers with the configured
 * {@link WhenStoreUnavailable} fallback instead of failing, and says so in the log.
 */
@Service
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** How often, at most, a continuing outage is logged again. */
    private static final long OUTAGE_LOG_INTERVAL_MILLIS = 30_000;

    /** What a refusal during an outage tells the caller to wait - the store may be back any moment. */
    static final long UNAVAILABLE_RETRY_AFTER_MILLIS = 1_000;

    private final ProxyManager<String> proxyManager;
    private final RateLimiterProperties props;
    private final Map<Resource, BucketConfiguration> configurations = new EnumMap<>(Resource.class);
    private final Clock clock;

    private final AtomicBoolean storeDown = new AtomicBoolean();
    private final AtomicLong lastOutageLog = new AtomicLong();
    private final AtomicLong degradedSinceLastLog = new AtomicLong();

    @Autowired
    public RateLimiter(RemoteCacheManager cacheManager, RateLimiterProperties props, Clock clock) {
        this(new LazyCache(cacheManager), props, clock);
    }

    RateLimiter(Supplier<RemoteCache<String, byte[]>> buckets, RateLimiterProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.proxyManager = new HotRodProxyManager(buckets, props.bucketLifespan(),
                ClientSideConfig.getDefault().withClientClock(wallClock()));
        props.resources().forEach((resource, policy) -> configurations.put(resource, bucketConfiguration(policy)));
    }

    /**
     * The buckets cache, looked up on first use rather than at startup: the lookup is a round trip
     * (it creates the cache when the server lacks it), and an instance that starts while the server is
     * down should come up and serve the fallback, then connect once the server is back.
     */
    private static final class LazyCache implements Supplier<RemoteCache<String, byte[]>> {
        private final RemoteCacheManager cacheManager;
        private volatile RemoteCache<String, byte[]> cache;

        LazyCache(RemoteCacheManager cacheManager) {
            this.cacheManager = cacheManager;
        }

        @Override
        public RemoteCache<String, byte[]> get() {
            RemoteCache<String, byte[]> resolved = cache;
            if (resolved == null) {
                resolved = cacheManager.getCache(HotRodConfig.BUCKETS_CACHE);
                if (resolved == null) {
                    throw new HotRodClientException("Cache " + HotRodConfig.BUCKETS_CACHE + " is not available");
                }
                cache = resolved;
            }
            return resolved;
        }
    }

    /**
     * Wall-clock rather than Bucket4j's default {@code nanoTime}: buckets outlive this JVM and are read
     * by other instances, and only wall-clock time means the same thing in a new JVM and on another host.
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
        // lets an allowed call report its countdown without another round trip to the server.
        VerboseResult<ConsumptionProbe> verbose;
        try {
            verbose = bucket.asVerbose().tryConsumeAndReturnRemaining(tokens);
        } catch (HotRodClientException e) {
            return storeUnavailable(policy, e);
        }
        storeReachable();
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

    private Decision storeUnavailable(ResourcePolicy policy, HotRodClientException cause) {
        boolean allow = props.whenStoreUnavailable() == WhenStoreUnavailable.ALLOW;
        long degraded = degradedSinceLastLog.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastOutageLog.get();
        boolean firstFailure = storeDown.compareAndSet(false, true);
        if ((firstFailure || now - last >= OUTAGE_LOG_INTERVAL_MILLIS) && lastOutageLog.compareAndSet(last, now)) {
            degradedSinceLastLog.addAndGet(-degraded);
            log.warn("Bucket store unavailable, {} all calls until it is back (ratelimiter.when-store-unavailable={}); "
                            + "{} degraded answers since the last warning: {}",
                    allow ? "allowing" : "denying", props.whenStoreUnavailable(), degraded, cause.toString());
        }
        return allow
                ? new Decision(true, policy.capacity(), 0, 0, true)
                : new Decision(false, policy.capacity(), 0, UNAVAILABLE_RETRY_AFTER_MILLIS, true);
    }

    private void storeReachable() {
        if (storeDown.compareAndSet(true, false)) {
            log.info("Bucket store reachable again, limiting resumed");
        }
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
