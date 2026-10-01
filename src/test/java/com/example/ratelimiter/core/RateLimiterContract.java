package com.example.ratelimiter.core;

import com.example.ratelimiter.MutableClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the limiter must do over any bucket store: exact capacity under concurrency, one bucket shared
 * by several instances, stepwise refill, retry-after, the per-write lifespan. Run over the in-memory
 * fake by {@link RateLimiterTest} on every build, and over a real Infinispan server by
 * {@link RateLimiterServerTest} wherever Docker is available.
 */
abstract class RateLimiterContract {

    static final Resource SUBJECT = Resource.SUBJECT_SEARCH;  // 15 burst, +6 per minute
    static final Resource DAILY = Resource.MAX_CALLS_IP;      // 100k per day

    static final Map<Resource, ResourcePolicy> POLICIES = Map.of(
            Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
            Resource.NEW_CASES, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
            Resource.MAX_CALLS_IP, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)),
            Resource.MAX_CALLS_SESS, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)));

    final RateLimiterProperties props = new RateLimiterProperties(POLICIES);
    MutableClock clock;
    RateLimiter limiter;

    /** Empties the store; {@link #clock} is already fresh. */
    abstract void resetStore();

    /** A limiter as another instance would have one: its own client, the same store, {@link #clock}. */
    abstract RateLimiter newInstance(RateLimiterProperties properties);

    /** The lifespan the store holds for a bucket, in seconds. */
    abstract long storedLifespanSeconds(String bucket);

    @BeforeEach
    void setUpLimiter() {
        clock = new MutableClock();
        resetStore();
        limiter = newInstance(props);
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
    void twoInstancesShareOneBucket() throws Exception {
        // Two clients, as two pods would have: the quota is the store's, and concurrent checks
        // through both still admit exactly the capacity.
        RateLimiter other = newInstance(props);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            RateLimiter instance = i % 2 == 0 ? limiter : other;
            results.add(pool.submit(() -> {
                start.await();
                return instance.check(SUBJECT, "shared").allowed();
            }));
        }
        start.countDown();

        long allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) allowed++;
        }
        pool.shutdownNow();

        assertThat(allowed).isEqualTo(15);
        assertThat(other.check(SUBJECT, "shared").remaining()).isZero();
    }

    @Test
    void bucketsAreWrittenWithTheDerivedLifespan() {
        limiter.check(SUBJECT, "ip");

        assertThat(storedLifespanSeconds(RateLimiter.bucketName(SUBJECT, "ip")))
                .isEqualTo(props.bucketLifespan().toSeconds());
    }

    @Test
    void reachableStoreAnswersAreNotDegraded() {
        assertThat(limiter.check(SUBJECT, "ip").degraded()).isFalse();
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

        clock.advance(Duration.ofSeconds(30));                    // mid-period: nothing yet
        assertThat(limiter.check(SUBJECT, "ip").allowed()).isFalse();
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(30_000);

        clock.advance(Duration.ofSeconds(30));                    // period boundary: +6 tokens
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

        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(45_000);

        clock.advance(Duration.ofMinutes(1));                              // t=75s: the 60s refill has landed,
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(45_000);  // next is at 120s
    }

    @Test
    void retryAfterCountsDownWithinThePeriodWhileRefused() {
        for (int i = 0; i < 15; i++) limiter.check(SUBJECT, "ip");

        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(60_000);
        clock.advance(Duration.ofSeconds(6));
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(54_000);
        clock.advance(Duration.ofSeconds(48));
        assertThat(limiter.check(SUBJECT, "ip").retryAfterMillis()).isEqualTo(6_000);

        clock.advance(Duration.ofSeconds(6));                         // the boundary itself
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

        clock.advance(Duration.ofMinutes(100));                  // far more refills than fit
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

        clock.advance(Duration.ofDays(1));
        assertThat(limiter.check(DAILY, "ip").allowed()).isTrue();
    }
}
