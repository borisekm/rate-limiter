package com.example.ratelimiter.config;

import com.example.ratelimiter.InMemoryRemoteCache;
import com.example.ratelimiter.MutableClock;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** The three answers {@code bucketStore} health can give, without a server. */
class BucketStoreHealthIndicatorTest {

    private final InMemoryRemoteCache store = new InMemoryRemoteCache(HotRodConfig.BUCKETS_CACHE, new MutableClock());
    private final RemoteCacheManager cacheManager = mock(RemoteCacheManager.class);
    private final BucketStoreHealthIndicator indicator = new BucketStoreHealthIndicator(cacheManager);

    @BeforeEach
    void setUp() {
        doReturn(new String[]{"dg-1:11222", "dg-2:11222"}).when(cacheManager).getServers();
    }

    @Test
    void upWhenTheCacheAnswers() {
        doReturn(store.asRemoteCache()).when(cacheManager).getCache(HotRodConfig.BUCKETS_CACHE);

        Health health = indicator.health(true);
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("servers", "dg-1:11222;dg-2:11222")
                .containsEntry("cache", HotRodConfig.BUCKETS_CACHE);
    }

    @Test
    void downWhenTheServerLacksTheCache() {
        Health health = indicator.health(true);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("cache").toString()).contains("not available");
    }

    @Test
    void downWhenTheServerIsUnreachable() {
        doReturn(store.asRemoteCache()).when(cacheManager).getCache(HotRodConfig.BUCKETS_CACHE);
        store.goDown();

        assertThat(indicator.health(true).getStatus()).isEqualTo(Status.DOWN);
    }
}
