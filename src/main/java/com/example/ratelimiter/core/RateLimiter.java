package com.example.ratelimiter.core;

import com.example.ratelimiter.config.InfinispanConfig;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class RateLimiter {

    private final Cache<String, Bucket> buckets;
    private final RateLimiterProperties props;
    private final Clock clock;

    @Autowired
    public RateLimiter(EmbeddedCacheManager cacheManager, RateLimiterProperties props, Clock clock) {
        this(cacheManager.<String, Bucket>getCache(InfinispanConfig.BUCKETS_CACHE), props, clock);
    }

    RateLimiter(Cache<String, Bucket> buckets, RateLimiterProperties props, Clock clock) {
        this.buckets = buckets;
        this.props = props;
        this.clock = clock;
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
        long now = clock.millis();
        long periodMillis = policy.refillPeriod().toMillis();

        Bucket bucket = buckets.compute(bucketName(resource, identifier),
                new ConsumeTokens(tokens, now, policy.capacity(), policy.refillTokens(), periodMillis));

        // Periods needed before the bucket holds enough tokens again: none when the call went
        // through, in which case this is simply the time to the next refill.
        long periodsNeeded = 1;
        if (!bucket.lastAllowed()) {
            if (tokens > policy.capacity()) {
                // More than the bucket can ever hold - no amount of waiting helps.
                return new Decision(false, policy.capacity(), bucket.tokens(), Long.MAX_VALUE);
            }
            long deficit = tokens - bucket.tokens();
            periodsNeeded = ConsumeTokens.ceilDiv(deficit, policy.refillTokens());
        }
        long nextRefillMillis = bucket.refillAnchorEpochMillis() + periodsNeeded * periodMillis;
        long retryAfter = Math.max(0, nextRefillMillis - now);

        return new Decision(bucket.lastAllowed(), policy.capacity(), bucket.tokens(), retryAfter);
    }

    /** Bucket naming scheme: {@code <resource>@<identifier>}. */
    static String bucketName(Resource resource, String identifier) {
        return resource.getValue() + "@" + identifier;
    }
}
