package com.example.ratelimiter.config;

import com.example.ratelimiter.MutableClock;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
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
import static org.awaitility.Awaitility.await;

/**
 * Two nodes in one JVM, wired with the real {@link InfinispanConfig}. Buckets and the
 * {@code ConsumeTokens} function are then genuinely marshalled between nodes, which is the one thing
 * the single-node tests cannot cover - and where a wrong user marshaller shows up as ISPN000936.
 */
class ClusteredMarshallingTest {

    private static final Resource RESOURCE = Resource.SUBJECT_SEARCH;

    /**
     * Static loopback discovery instead of the bundled stack's multicast: on a machine whose first
     * site-local address belongs to a VPN or VMware/Hyper-V adapter, MPING finds nothing and the two
     * nodes each form a cluster of one, failing this test for reasons that have nothing to do with it.
     */
    static final String TEST_JGROUPS_STACK = "jgroups-test-tcpping.xml";

    @TempDir
    private Path storeRoot;

    private RateLimiterProperties props;
    private DefaultCacheManager nodeA;
    private DefaultCacheManager nodeB;
    private RateLimiter limiterA;
    private RateLimiter limiterB;

    @BeforeEach
    void setUp() {
        // A cluster of its own, so the test never joins a locally running instance.
        String clusterName = "test-" + UUID.randomUUID();
        props = new RateLimiterProperties(Map.of(
                Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.NEW_CASES, new ResourcePolicy(15, 6, Duration.ofMinutes(1)),
                Resource.MAX_CALLS_IP, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1)),
                Resource.MAX_CALLS_SESS, new ResourcePolicy(100_000, 100_000, Duration.ofDays(1))));
        MutableClock clock = new MutableClock();

        // Each node gets its own store directory - they must never share one.
        nodeA = startNode(clusterName, "node-a");
        nodeB = startNode(clusterName, "node-b");
        limiterA = new RateLimiter(nodeA, props, clock);
        limiterB = new RateLimiter(nodeB, props, clock);
        awaitOneClusterOfTwo();
    }

    /**
     * Fails with the actual cause when the nodes do not find each other, rather than leaving the test
     * body to report a puzzling "expected false but was true" - each node would then have its own
     * bucket and every shared-limit assertion would be wrong for a reason invisible in the message.
     */
    private void awaitOneClusterOfTwo() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(nodeA.getMembers())
                        .as("nodes did not form one cluster - discovery failed, see %s", TEST_JGROUPS_STACK)
                        .hasSize(2)
                        .isEqualTo(nodeB.getMembers()));
    }

    private DefaultCacheManager startNode(String clusterName, String nodeDirectory) {
        InfinispanConfig config =
                new InfinispanConfig(clusterName, storeRoot.resolve(nodeDirectory).toString(), true,
                        TEST_JGROUPS_STACK);
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
