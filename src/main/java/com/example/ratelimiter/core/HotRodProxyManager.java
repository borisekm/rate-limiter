package com.example.ratelimiter.core;

import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AbstractCompareAndSwapBasedProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AsyncCompareAndSwapOperation;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.CompareAndSwapOperation;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.MetadataValue;
import org.infinispan.client.hotrod.RemoteCache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bucket4j over a Hot Rod {@link RemoteCache}, by optimistic compare-and-swap.
 *
 * <p>Bucket4j's own Infinispan module ships an entry processor to the key's owner, which only an
 * embedded cache can run. Over Hot Rod the equivalent is a versioned write: read the bucket with its
 * version, let Bucket4j apply the command locally, and write the result back only if the version is
 * still the one read ({@code replaceWithVersion}; {@code putIfAbsent} for a bucket that did not exist).
 * A lost race returns {@code false} and Bucket4j re-reads and retries, so concurrent checks on any
 * instance are serialised per bucket with no lock held anywhere. Every other write path would lose
 * updates - never write a bucket unversioned.
 */
final class HotRodProxyManager extends AbstractCompareAndSwapBasedProxyManager<String> {

    private final Supplier<RemoteCache<String, byte[]>> buckets;
    private final long lifespanMillis;

    /**
     * @param buckets  the cache, resolved per request so an instance started while the server was
     *                 unreachable connects once it is back
     * @param lifespan applied on every write; see {@code RateLimiterProperties.bucketLifespan()}
     */
    HotRodProxyManager(Supplier<RemoteCache<String, byte[]>> buckets, Duration lifespan, ClientSideConfig config) {
        super(config);
        this.buckets = buckets;
        this.lifespanMillis = lifespan.toMillis();
    }

    @Override
    protected CompareAndSwapOperation beginCompareAndSwapOperation(String key) {
        RemoteCache<String, byte[]> cache = buckets.get();
        return new CompareAndSwapOperation() {
            /** Version of the state last returned by getStateData; each retry reads it afresh. */
            private long version;

            @Override
            public Optional<byte[]> getStateData(Optional<Long> timeoutNanos) {
                MetadataValue<byte[]> entry = cache.getWithMetadata(key);
                if (entry == null) {
                    return Optional.empty();
                }
                version = entry.getVersion();
                return Optional.of(entry.getValue());
            }

            @Override
            public boolean compareAndSwap(byte[] originalData, byte[] newData, RemoteBucketState newState,
                                          Optional<Long> timeoutNanos) {
                if (originalData == null) {
                    // Without FORCE_RETURN_VALUE Hot Rod answers null whether or not the put happened.
                    return cache.withFlags(Flag.FORCE_RETURN_VALUE)
                            .putIfAbsent(key, newData, lifespanMillis, TimeUnit.MILLISECONDS) == null;
                }
                return cache.replaceWithVersion(key, newData, version,
                        lifespanMillis, TimeUnit.MILLISECONDS, 0, TimeUnit.MILLISECONDS);
            }
        };
    }

    @Override
    protected AsyncCompareAndSwapOperation beginAsyncCompareAndSwapOperation(String key) {
        throw new UnsupportedOperationException("RateLimiter checks synchronously");
    }

    @Override
    public boolean isAsyncModeSupported() {
        return false;
    }

    @Override
    public void removeProxy(String key) {
        buckets.get().remove(key);
    }

    @Override
    protected CompletableFuture<Void> removeAsync(String key) {
        throw new UnsupportedOperationException("RateLimiter checks synchronously");
    }
}
