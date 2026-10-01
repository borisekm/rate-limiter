package com.example.ratelimiter.core;

import com.example.ratelimiter.HotRodTestServer;
import com.example.ratelimiter.config.HotRodConfig;
import com.example.ratelimiter.config.RateLimiterProperties;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RateLimiterContract} over a real Infinispan server ({@link HotRodTestServer}), so the
 * compare-and-swap path runs over the wire protocol it runs over in production. Skipped where there is
 * no Docker (the CI build); {@link RateLimiterTest} runs the same contract over the in-memory fake.
 */
@EnabledIf("com.example.ratelimiter.HotRodTestServer#dockerAvailable")
class RateLimiterServerTest extends RateLimiterContract {

    private static RemoteCacheManager client;
    private static RemoteCache<String, byte[]> cache;

    /** Clients opened by {@link #newInstance} beyond the shared one, closed after each test. */
    private final List<RemoteCacheManager> otherClients = new ArrayList<>();

    @BeforeAll
    static void connect() {
        client = HotRodTestServer.newClient();
        cache = client.getCache(HotRodConfig.BUCKETS_CACHE);   // creates it, as the app would
    }

    @AfterAll
    static void disconnect() {
        client.stop();
    }

    @AfterEach
    void closeOtherClients() {
        otherClients.forEach(RemoteCacheManager::stop);
    }

    @Override
    void resetStore() {
        cache.clear();
    }

    @Override
    RateLimiter newInstance(RateLimiterProperties properties) {
        if (limiter == null) {
            return new RateLimiter(client, properties, clock);
        }
        RemoteCacheManager own = HotRodTestServer.newClient();
        otherClients.add(own);
        return new RateLimiter(own, properties, clock);
    }

    @Override
    long storedLifespanSeconds(String bucket) {
        return cache.getWithMetadata(bucket).getLifespan();
    }
}
