package com.example.ratelimiter.core;

import com.example.ratelimiter.HotRodTestServer;
import com.example.ratelimiter.InMemoryRemoteCache;
import com.example.ratelimiter.api.model.CheckRateRequest;
import com.example.ratelimiter.config.HotRodConfig;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.RateLimiterProperties.ResourcePolicy;
import com.example.ratelimiter.config.RateLimiterProperties.WhenStoreUnavailable;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * {@link RateLimiterContract} over {@link InMemoryRemoteCache}, so it runs with no container runtime,
 * plus what only a fake can stage on demand - an outage and a recovery mid-test, a server without the
 * cache - and the configuration checks. {@link RateLimiterServerTest} runs the contract over a real
 * server where Docker is available.
 */
@ExtendWith(OutputCaptureExtension.class)
class RateLimiterTest extends RateLimiterContract {

    private InMemoryRemoteCache store;

    @Override
    void resetStore() {
        store = new InMemoryRemoteCache(HotRodConfig.BUCKETS_CACHE, clock);
    }

    /** Through the public constructor, so the lazy cache lookup is the one the app uses. */
    @Override
    RateLimiter newInstance(RateLimiterProperties properties) {
        RemoteCacheManager client = mock(RemoteCacheManager.class);
        doReturn(store.asRemoteCache()).when(client).getCache(HotRodConfig.BUCKETS_CACHE);
        return new RateLimiter(client, properties, clock);
    }

    @Override
    long storedLifespanSeconds(String bucket) {
        return store.asRemoteCache().getWithMetadata(bucket).getLifespan();
    }

    @Test
    void outageFailsClosedWhenConfiguredTo() {
        store.goDown();

        Decision decision = limiter.check(SUBJECT, "ip");
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.limit()).isEqualTo(15);
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterMillis()).isEqualTo(RateLimiter.UNAVAILABLE_RETRY_AFTER_MILLIS);
    }

    @Test
    void outageFailsOpenWhenConfiguredTo() {
        RateLimiter open = newInstance(new RateLimiterProperties(POLICIES, WhenStoreUnavailable.ALLOW));
        store.goDown();

        for (int i = 0; i < 20; i++) {                        // past capacity: nothing is counted
            Decision decision = open.check(SUBJECT, "ip");
            assertThat(decision.degraded()).isTrue();
            assertThat(decision.allowed()).isTrue();
        }
    }

    @Test
    void countingResumesWhereItLeftOffOnceTheStoreIsBack(CapturedOutput output) {
        for (int i = 0; i < 10; i++) limiter.check(SUBJECT, "ip");
        store.goDown();
        limiter.check(SUBJECT, "ip");
        store.comeBack();

        Decision decision = limiter.check(SUBJECT, "ip");
        assertThat(decision.degraded()).isFalse();
        assertThat(decision.remaining()).isEqualTo(4);        // the outage consumed nothing
        assertThat(output).contains("Bucket store reachable again");
    }

    @Test
    void outageWarningIsThrottledAndCountsWhatItSkipped(CapturedOutput output) {
        store.goDown();
        for (int i = 0; i < 5; i++) limiter.check(SUBJECT, "ip");
        assertThat(countOf(output, "Bucket store unavailable")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        limiter.check(SUBJECT, "ip");
        assertThat(countOf(output, "Bucket store unavailable")).isEqualTo(2);
        assertThat(output).contains("5 degraded answers since the last warning");
    }

    @Test
    void aServerWithoutTheCacheIsAnOutage() {
        RemoteCacheManager client = mock(RemoteCacheManager.class);   // getCache answers null
        RateLimiter cut = new RateLimiter(client, props, clock);

        assertThat(cut.check(SUBJECT, "ip").degraded()).isTrue();
    }

    /** The real client against an address nothing listens on - the exception the fake stands in for. */
    @Test
    void unreachableServerFailsClosedWhenConfiguredTo() {
        try (RemoteCacheManager dead = HotRodTestServer.client(HotRodTestServer.deadAddress())) {
            RateLimiter cut = new RateLimiter(dead, new RateLimiterProperties(POLICIES, WhenStoreUnavailable.DENY), clock);

            Decision decision = cut.check(SUBJECT, "ip");
            assertThat(decision.degraded()).isTrue();
            assertThat(decision.allowed()).isFalse();
        }
    }

    @Test
    void unreachableServerFailsOpenWhenConfiguredTo() {
        try (RemoteCacheManager dead = HotRodTestServer.client(HotRodTestServer.deadAddress())) {
            RateLimiter cut = new RateLimiter(dead, new RateLimiterProperties(POLICIES, WhenStoreUnavailable.ALLOW), clock);

            Decision decision = cut.check(SUBJECT, "ip");
            assertThat(decision.degraded()).isTrue();
            assertThat(decision.allowed()).isTrue();
        }
    }

    @Test
    void everyResourceNeedsAPolicy() {
        Map<Resource, ResourcePolicy> incomplete =
                Map.of(Resource.SUBJECT_SEARCH, new ResourcePolicy(15, 6, Duration.ofMinutes(1)));
        assertThatThrownBy(() -> new RateLimiterProperties(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newCases");
        assertThatThrownBy(() -> new RateLimiterProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subjectSearch");
    }

    @Test
    void lifespanCoversTheSlowestPolicysTimeToFull() {
        // 15 tokens at 6 per minute is three periods; a day's quota refills in one day.
        assertThat(POLICIES.get(SUBJECT).timeToFull()).isEqualTo(Duration.ofMinutes(3));
        assertThat(props.bucketLifespan()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void resourceMirrorsTheGeneratedApiEnum() {
        assertThat(Arrays.stream(CheckRateRequest.ResourceEnum.values()).map(e -> e.getValue()).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Resource.values()).map(Resource::getValue).toList());
        assertThat(Resource.fromValue("subjectSearch")).isEqualTo(Resource.SUBJECT_SEARCH);
        assertThat(Resource.fromValue("subject-search")).isEqualTo(Resource.SUBJECT_SEARCH);
        assertThat(Resource.SUBJECT_SEARCH).hasToString("subjectSearch");
        assertThatThrownBy(() -> Resource.fromValue("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Resource.fromValue(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int countOf(CapturedOutput output, String text) {
        return output.getAll().split(java.util.regex.Pattern.quote(text), -1).length - 1;
    }
}
