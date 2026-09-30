package com.example.ratelimiter.config;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Whether the bucket store answers, as {@code bucketStore} under {@code /actuator/health}.
 *
 * <p>Deliberately not part of the readiness group: with the store down every instance is equally
 * degraded, and taking them all out of the Service would turn a fail-open fallback into an outage.
 */
@Component
class BucketStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE_KEY = "health-probe";

    private final RemoteCacheManager cacheManager;

    BucketStoreHealthIndicator(RemoteCacheManager cacheManager) {
        super("Bucket store health check failed");
        this.cacheManager = cacheManager;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("servers", String.join(";", cacheManager.getServers()));
        RemoteCache<String, byte[]> cache = cacheManager.getCache(HotRodConfig.BUCKETS_CACHE);
        if (cache == null) {
            builder.down().withDetail("cache", HotRodConfig.BUCKETS_CACHE + " not available");
            return;
        }
        cache.containsKey(PROBE_KEY);   // a round trip; throws when the server is unreachable
        builder.up().withDetail("cache", HotRodConfig.BUCKETS_CACHE);
    }
}
