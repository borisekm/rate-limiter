package com.example.ratelimiter.core;

import com.example.ratelimiter.config.InfinispanConfig;
import com.example.ratelimiter.config.NanoClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RateLimiter {

    private final Cache<String, Bucket> buckets;
    private final RateLimiterProperties props;
    private final NanoClock clock;

    @Autowired
    public RateLimiter(EmbeddedCacheManager cacheManager, RateLimiterProperties props, NanoClock clock) {
        this(cacheManager.<String, Bucket>getCache(InfinispanConfig.BUCKETS_CACHE), props, clock);
    }

    RateLimiter(Cache<String, Bucket> buckets, RateLimiterProperties props, NanoClock clock) {
        this.buckets = buckets;
        this.props = props;
        this.clock = clock;
    }

    public Decision check(String key, int tokens) {
        Bucket bucket = buckets.compute(key,
                new ConsumeTokens(tokens, clock.nanos(), props.capacity(), props.refillPerSecond()));

        long retryAfter = 0;
        if (!bucket.lastAllowed()) {
            double deficit = tokens - bucket.tokens();
            retryAfter = props.refillPerSecond() > 0
                    ? (long) Math.ceil(deficit / props.refillPerSecond() * 1000)
                    : Long.MAX_VALUE;
        }
        return new Decision(bucket.lastAllowed(), props.capacity(), (long) Math.floor(bucket.tokens()), retryAfter);
    }
}
