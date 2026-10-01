package com.example.ratelimiter.api;

import com.example.ratelimiter.InMemoryRemoteCache;
import com.example.ratelimiter.config.HotRodConfig;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;

import static org.mockito.Mockito.doReturn;

/**
 * {@link RateLimitApiContract} with the starter's {@code RemoteCacheManager} replaced by a mock that
 * hands out {@link InMemoryRemoteCache}, so it runs with no container runtime.
 */
class RateLimitApiTest extends RateLimitApiContract {

    private static final InMemoryRemoteCache STORE =
            new InMemoryRemoteCache(HotRodConfig.BUCKETS_CACHE, Clock.systemUTC());

    @MockitoBean
    private RemoteCacheManager cacheManager;

    @BeforeEach
    void serveTheFake() {
        doReturn(STORE.asRemoteCache()).when(cacheManager).getCache(HotRodConfig.BUCKETS_CACHE);
        doReturn(new String[]{"in-memory"}).when(cacheManager).getServers();
    }
}
