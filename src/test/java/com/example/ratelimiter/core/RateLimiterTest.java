package com.example.ratelimiter.core;

import com.example.ratelimiter.config.NanoClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long CAPACITY = 100;
    private static final double REFILL = 100;

    private DefaultCacheManager cacheManager;
    private AtomicLong now;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        cacheManager = new DefaultCacheManager();
        cacheManager.defineConfiguration("buckets", new ConfigurationBuilder().build());
        Cache<String, Bucket> cache = cacheManager.getCache("buckets");
        now = new AtomicLong(0);
        NanoClock clock = now::get;
        limiter = new RateLimiter(cache, new RateLimiterProperties(CAPACITY, REFILL, 60_000), clock);
    }

    @AfterEach
    void tearDown() {
        cacheManager.stop();
    }

    @Test
    void admitsExactlyCapacityUnderConcurrency() throws Exception {
        int attempts = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return limiter.check("hot-key", 1).allowed();
            }));
        }
        start.countDown();

        long allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) allowed++;
        }
        pool.shutdownNow();

        assertThat(allowed).isEqualTo(CAPACITY);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.check("k", 1).allowed()).isTrue();
        }
        Decision denied = limiter.check("k", 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(10); // 1 token at 100/s

        now.addAndGet(500_000_000L); // +0.5 s -> 50 tokens
        Decision after = limiter.check("k", 1);
        assertThat(after.allowed()).isTrue();
        assertThat(after.remaining()).isEqualTo(49);
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < CAPACITY; i++) limiter.check("a", 1);
        assertThat(limiter.check("a", 1).allowed()).isFalse();
        assertThat(limiter.check("b", 1).allowed()).isTrue();
    }
}
