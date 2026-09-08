package com.example.ratelimiter.config;

import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.core.Resource;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two nodes in one JVM, wired with the real {@link InfinispanConfig}. Buckets and the
 * {@code ConsumeTokens} function are then genuinely marshalled between nodes, which is the one thing
 * the single-node tests cannot cover - and where a wrong user marshaller shows up as ISPN000936.
 */
class ClusteredMarshallingTest {

    private static final Resource RESOURCE = Resource.SUBJECT_SEARCH;

    private RateLimiterProperties props;
    private DefaultCacheManager nodeA;
    private DefaultCacheManager nodeB;
    private RateLimiter limiterA;
    private RateLimiter limiterB;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        // A cluster of its own, so the test never joins a locally running instance.
        InfinispanConfig config = new InfinispanConfig("test-" + UUID.randomUUID());
        props = new RateLimiterProperties(Map.of(
                Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.NEW_CASES, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.MAX_CALLS_IP, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)),
                Resource.MAX_CALLS_SESS, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1))));
        now = new AtomicLong(0);
        NanoClock clock = now::get;

        nodeA = startNode(config);
        nodeB = startNode(config);
        limiterA = new RateLimiter(nodeA, props, clock);
        limiterB = new RateLimiter(nodeB, props, clock);
    }

    private DefaultCacheManager startNode(InfinispanConfig config) {
        DefaultCacheManager manager = new DefaultCacheManager(config.globalConfigurer().getGlobalConfiguration());
        config.bucketsCacheConfigurer(props).configureCache(manager);
        return manager;
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.stop();
        if (nodeB != null) nodeB.stop();
    }

    @Test
    void bothNodesShareOneLimitPerBucket() {
        // Enough distinct identifiers that some buckets are owned by the other node, so the
        // consume function has to travel.
        for (int i = 0; i < 20; i++) {
            String identifier = "ip-" + i;
            for (int call = 0; call < 15; call++) {
                RateLimiter limiter = call % 2 == 0 ? limiterA : limiterB;
                assertThat(limiter.check(RESOURCE, identifier).allowed())
                        .as("call %d for %s", call, identifier)
                        .isTrue();
            }
            // The 16th call is refused from either node - the bucket is shared, not per node.
            assertThat(limiterA.check(RESOURCE, identifier).allowed()).isFalse();
            assertThat(limiterB.check(RESOURCE, identifier).allowed()).isFalse();
        }
    }
}
