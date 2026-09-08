package com.example.ratelimiter.core;

import com.example.ratelimiter.api.model.CheckRateRequest;
import com.example.ratelimiter.config.NanoClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private static final Resource SUBJECT = Resource.SUBJECT_SEARCH;  // 15 burst, +6 per minute
    private static final Resource DAILY = Resource.MAX_CALLS_IP;      // 100k per day

    private static final long MINUTE_NANOS = Duration.ofMinutes(1).toNanos();

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
        RateLimiterProperties props = new RateLimiterProperties(Map.of(
                Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.NEW_CASES, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.MAX_CALLS_IP, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)),
                Resource.MAX_CALLS_SESS, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1))));
        limiter = new RateLimiter(cache, props, clock);
    }

    @AfterEach
    void tearDown() {
        cacheManager.stop();
    }

    @Test
    void admitsExactlyCapacityUnderConcurrency() throws Exception {
        int attempts = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return limiter.check(SUBJECT, "10.0.0.1").allowed();
            }));
        }
        start.countDown();

        long allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) allowed++;
        }
        pool.shutdownNow();

        assertThat(allowed).isEqualTo(15);
    }

    @Test
    void refillsInWholePeriods() {
        for (int i = 0; i < 15; i++) {
            assertThat(limiter.check(SUBJECT, "ip").allowed()).isTrue();
        }
        Decision denied = limiter.check(SUBJECT, "ip");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMillis()).isEqualTo(60_000);

        now.addAndGet(MINUTE_NANOS / 2);                    // mid-period: nothing yet
        assertThat(limiter.check(SUBJECT, "ip").allowed()).isFalse();
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(30_000);

        now.addAndGet(MINUTE_NANOS / 2);                    // period boundary: +6 tokens
        Decision after = limiter.check(SUBJECT, "ip");
        assertThat(after.allowed()).isTrue();
        assertThat(after.remaining()).isEqualTo(5);
        assertThat(after.limit()).isEqualTo(15);
    }

    @Test
    void allowedCallsReportTimeToTheNextRefill() {
        Decision first = limiter.check(SUBJECT, "ip");
        assertThat(first.allowed()).isTrue();
        assertThat(first.retryAfterMillis()).isEqualTo(60_000);   // bucket created now, refill in a period

        now.addAndGet(MINUTE_NANOS / 4);
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(45_000);

        now.addAndGet(MINUTE_NANOS);                              // t=75s: the 60s refill has landed,
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(45_000);  // next is at 120s
    }

    @Test
    void retryAfterCountsDownWithinThePeriodWhileRefused() {
        for (int i = 0; i < 15; i++) limiter.check(SUBJECT, "ip");

        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(60_000);
        now.addAndGet(MINUTE_NANOS / 10);
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(54_000);
        now.addAndGet(MINUTE_NANOS / 10 * 8);
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(6_000);

        now.addAndGet(MINUTE_NANOS / 10);                         // the boundary itself
        Decision refilled = limiter.check(SUBJECT, "ip");
        assertThat(refilled.allowed()).isTrue();
        assertThat(refilled.retryAfterMillis()).isEqualTo(60_000); // a fresh period starts
    }

    @Test
    void retryAfterSpansSeveralPeriodsWhenOnePeriodIsNotEnough() {
        for (int i = 0; i < 15; i++) limiter.check(SUBJECT, "ip");

        // 6 tokens per period, so a 13-token call has to wait out three of them.
        assertThat(limiter.check(SUBJECT, "ip", 13).retryAfterMillis()).isEqualTo(3 * 60_000);
    }

    @Test
    void retryAfterUsesEachResourcesOwnPeriod() {
        assertThat(limiter.check(DAILY, "ip").retryAfterMillis())
                .isEqualTo(Duration.ofDays(1).toMillis());
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis())
                .isEqualTo(Duration.ofMinutes(1).toMillis());
    }

    @Test
    void retryAfterIsUnreachableForACallLargerThanCapacity() {
        Decision decision = limiter.check(SUBJECT, "ip", 16);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMillis()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void refillIsCappedAtCapacity() {
        for (int i = 0; i < 15; i++) limiter.check(SUBJECT, "ip");

        now.addAndGet(MINUTE_NANOS * 100);                  // far more refills than fit
        assertThat(limiter.check(SUBJECT, "ip").remaining()).isEqualTo(14);
    }

    @Test
    void bucketsAreScopedToResourceAndIdentifier() {
        for (int i = 0; i < 15; i++) limiter.check(SUBJECT, "ip-a");

        assertThat(limiter.check(SUBJECT, "ip-a").allowed()).isFalse();
        assertThat(limiter.check(SUBJECT, "ip-b").allowed()).isTrue();   // other identifier
        assertThat(limiter.check(DAILY, "ip-a").allowed()).isTrue();     // other resource

        assertThat(RateLimiter.bucketName(SUBJECT, "ip-a")).isEqualTo("subjectSearch@ip-a");
    }

    @Test
    void dailyQuotaRefillsOncePerDay() {
        Decision first = limiter.check(DAILY, "ip");
        assertThat(first.limit()).isEqualTo(100_000);
        assertThat(first.remaining()).isEqualTo(99_999);

        limiter.check(DAILY, "ip", 99_999);
        Decision denied = limiter.check(DAILY, "ip");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(Duration.ofDays(1).toMillis());

        now.addAndGet(Duration.ofDays(1).toNanos());
        assertThat(limiter.check(DAILY, "ip").allowed()).isTrue();
    }

    @Test
    void everyResourceNeedsAPolicy() {
        Map<Resource, ResourcePolicy> incomplete =
                Map.of(Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)));
        assertThatThrownBy(() -> new RateLimiterProperties(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newCases");
    }

    @Test
    void resourceMirrorsTheGeneratedApiEnum() {
        assertThat(Arrays.stream(CheckRateRequest.ResourceEnum.values()).map(e -> e.getValue()).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Resource.values()).map(Resource::getValue).toList());
        assertThat(Resource.fromValue("subjectSearch")).isEqualTo(Resource.SUBJECT_SEARCH);
        assertThat(Resource.fromValue("subject-search")).isEqualTo(Resource.SUBJECT_SEARCH);
    }
}
