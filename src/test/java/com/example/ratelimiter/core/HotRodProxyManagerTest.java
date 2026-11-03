package com.example.ratelimiter.core;

import com.example.ratelimiter.InMemoryRemoteCache;
import com.example.ratelimiter.MutableClock;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import org.infinispan.client.hotrod.RemoteCache;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the proxy manager the limiter does not drive through a check. */
class HotRodProxyManagerTest {

    private final RemoteCache<String, byte[]> cache =
            new InMemoryRemoteCache("buckets", new MutableClock()).asRemoteCache();
    private final HotRodProxyManager manager =
            new HotRodProxyManager(() -> cache, Duration.ofMinutes(1), ClientSideConfig.getDefault());

    @Test
    void removeProxyDeletesTheBucket() {
        manager.beginCompareAndSwapOperation("b").compareAndSwap(null, new byte[]{1}, null, Optional.empty());
        assertThat(cache.containsKey("b")).isTrue();

        manager.removeProxy("b");
        assertThat(cache.containsKey("b")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resourceRemovalUsesTheDelimiterAndClosesTheIterator() {
        RemoteCache<String, byte[]> remote = org.mockito.Mockito.mock(RemoteCache.class);
        var keys = org.mockito.Mockito.mock(org.infinispan.commons.util.CloseableIteratorSet.class);
        var iterator = org.mockito.Mockito.mock(org.infinispan.commons.util.CloseableIterator.class);
        org.mockito.Mockito.when(remote.keySet()).thenReturn(keys);
        org.mockito.Mockito.when(keys.iterator()).thenReturn(iterator);
        org.mockito.Mockito.when(iterator.hasNext()).thenReturn(true, true, true, false);
        org.mockito.Mockito.when(iterator.next()).thenReturn(
                "subjectSearch@a", "subjectSearchExtra@b", "newCases@subjectSearch@a");
        var proxy = new HotRodProxyManager(() -> remote, Duration.ofMinutes(1), ClientSideConfig.getDefault());

        proxy.removeResource(Resource.SUBJECT_SEARCH);

        org.mockito.Mockito.verify(remote).remove("subjectSearch@a");
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.never()).remove("subjectSearchExtra@b");
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.never()).remove("newCases@subjectSearch@a");
        org.mockito.Mockito.verify(iterator).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resourceRemovalClosesTheIteratorWhenDeletionFails() {
        RemoteCache<String, byte[]> remote = org.mockito.Mockito.mock(RemoteCache.class);
        var keys = org.mockito.Mockito.mock(org.infinispan.commons.util.CloseableIteratorSet.class);
        var iterator = org.mockito.Mockito.mock(org.infinispan.commons.util.CloseableIterator.class);
        org.mockito.Mockito.when(remote.keySet()).thenReturn(keys);
        org.mockito.Mockito.when(keys.iterator()).thenReturn(iterator);
        org.mockito.Mockito.when(iterator.hasNext()).thenReturn(true);
        org.mockito.Mockito.when(iterator.next()).thenReturn("subjectSearch@a");
        org.mockito.Mockito.when(remote.remove("subjectSearch@a"))
                .thenThrow(new org.infinispan.client.hotrod.exceptions.HotRodClientException("down"));
        var proxy = new HotRodProxyManager(() -> remote, Duration.ofMinutes(1), ClientSideConfig.getDefault());

        assertThatThrownBy(() -> proxy.removeResource(Resource.SUBJECT_SEARCH))
                .isInstanceOf(org.infinispan.client.hotrod.exceptions.HotRodClientException.class);
        org.mockito.Mockito.verify(iterator).close();
    }

    @Test
    void aLostRaceIsReportedNotOverwritten() {
        var first = manager.beginCompareAndSwapOperation("b");
        var second = manager.beginCompareAndSwapOperation("b");
        assertThat(first.getStateData(Optional.empty())).isEmpty();
        assertThat(second.getStateData(Optional.empty())).isEmpty();

        assertThat(first.compareAndSwap(null, new byte[]{1}, null, Optional.empty())).isTrue();
        assertThat(second.compareAndSwap(null, new byte[]{2}, null, Optional.empty())).isFalse();

        // Both read version v1; only one replace against it can win.
        byte[] state = first.getStateData(Optional.empty()).orElseThrow();
        second.getStateData(Optional.empty());
        assertThat(first.compareAndSwap(state, new byte[]{3}, null, Optional.empty())).isTrue();
        assertThat(second.compareAndSwap(state, new byte[]{4}, null, Optional.empty())).isFalse();
        assertThat(cache.getWithMetadata("b").getValue()).containsExactly(3);
    }

    @Test
    void asyncModeIsNotSupported() {
        assertThat(manager.isAsyncModeSupported()).isFalse();
        assertThatThrownBy(() -> manager.beginAsyncCompareAndSwapOperation("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> manager.removeAsync("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
