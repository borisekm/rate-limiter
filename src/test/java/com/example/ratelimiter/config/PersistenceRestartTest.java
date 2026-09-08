package com.example.ratelimiter.config;

import com.example.ratelimiter.MutableClock;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.core.Resource;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buckets outliving the process. A node is started against a store directory, used, stopped, and
 * started again against the same directory - which is what a deployment does, and what used to hand
 * every caller a full bucket.
 */
class PersistenceRestartTest {

    private static final Resource SUBJECT = Resource.SUBJECT_SEARCH;  // 15 burst, +6 per minute
    private static final Resource DAILY = Resource.MAX_CALLS_IP;      // 100k per day

    @TempDir
    private Path storeLocation;

    private String clusterName;
    private RateLimiterProperties props;
    private MutableClock clock;
    private DefaultCacheManager node;

    @BeforeEach
    void setUp() {
        clusterName = "test-" + UUID.randomUUID();
        props = new RateLimiterProperties(Map.of(
                Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.NEW_CASES, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.MAX_CALLS_IP, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)),
                Resource.MAX_CALLS_SESS, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1))));
        clock = new MutableClock();
    }

    @AfterEach
    void tearDown() {
        stop();
    }

    /** Starts a node against the store directory, as a fresh process would. */
    private RateLimiter start() {
        InfinispanConfig config = new InfinispanConfig(clusterName, storeLocation.toString(), true, "default-configs/default-jgroups-tcp.xml");
        node = new DefaultCacheManager(config.globalConfigurer().getGlobalConfiguration());
        config.bucketsCacheConfigurer(props).configureCache(node);
        return new RateLimiter(node, props, clock);
    }

    private void stop() {
        if (node != null) {
            node.stop();
            node = null;
        }
    }

    @Test
    void consumedTokensSurviveARestart() {
        RateLimiter before = start();
        for (int i = 0; i < 10; i++) {
            assertThat(before.check(SUBJECT, "10.0.0.7").allowed()).isTrue();
        }
        assertThat(before.check(SUBJECT, "10.0.0.7").remaining()).isEqualTo(4);
        stop();

        RateLimiter after = start();
        Decision decision = after.check(SUBJECT, "10.0.0.7");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(3);   // not 14 - the bucket was not handed back full
    }

    @Test
    void anExhaustedBucketStaysExhaustedAcrossARestart() {
        RateLimiter before = start();
        for (int i = 0; i < 15; i++) before.check(SUBJECT, "ip");
        assertThat(before.check(SUBJECT, "ip").allowed()).isFalse();
        stop();

        RateLimiter after = start();
        assertThat(after.check(SUBJECT, "ip").allowed()).isFalse();
    }

    @Test
    void refillsThatFellDueWhileDownAreAccountedFor() {
        RateLimiter before = start();
        for (int i = 0; i < 15; i++) before.check(SUBJECT, "ip");
        assertThat(before.check(SUBJECT, "ip").allowed()).isFalse();
        stop();

        clock.advance(Duration.ofMinutes(2));           // two periods pass with nothing running
        RateLimiter after = start();
        Decision decision = after.check(SUBJECT, "ip");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(11); // 6 + 6 refilled, 1 consumed
    }

    @Test
    void aDailyQuotaIsNotResetByADeploy() {
        RateLimiter before = start();
        before.check(DAILY, "ip", 60_000);
        assertThat(before.check(DAILY, "ip").remaining()).isEqualTo(39_999);
        stop();

        clock.advance(Duration.ofHours(6));             // still the same daily period
        RateLimiter after = start();
        assertThat(after.check(DAILY, "ip").remaining()).isEqualTo(39_998);
    }

    @Test
    void bucketsAreGoneWhenTheStoreIsDisabled() {
        InfinispanConfig config = new InfinispanConfig(clusterName, storeLocation.toString(), false, "default-configs/default-jgroups-tcp.xml");
        node = new DefaultCacheManager(config.globalConfigurer().getGlobalConfiguration());
        config.bucketsCacheConfigurer(props).configureCache(node);
        RateLimiter before = new RateLimiter(node, props, clock);
        for (int i = 0; i < 10; i++) before.check(SUBJECT, "ip");
        stop();

        node = new DefaultCacheManager(config.globalConfigurer().getGlobalConfiguration());
        config.bucketsCacheConfigurer(props).configureCache(node);
        RateLimiter after = new RateLimiter(node, props, clock);
        assertThat(after.check(SUBJECT, "ip").remaining()).isEqualTo(14);   // full again
    }
}
