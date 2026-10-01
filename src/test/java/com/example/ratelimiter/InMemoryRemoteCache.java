package com.example.ratelimiter;

import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.MetadataValue;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.client.hotrod.impl.MetadataValueImpl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-JVM stand-in for the buckets {@link RemoteCache}, for builds with no container runtime.
 *
 * <p>It keeps the Hot Rod semantics the limiter depends on, so the tests that would catch a broken
 * compare-and-swap still catch it here:
 * <ul>
 *   <li>every write bumps the entry's version, and {@code replaceWithVersion} succeeds only against
 *       the version last read;</li>
 *   <li>{@code putIfAbsent} returns the previous value only with {@link Flag#FORCE_RETURN_VALUE},
 *       and {@code null} either way without it, as Hot Rod does;</li>
 *   <li>lifespans expire entries on the given clock and are reported in seconds;</li>
 *   <li>{@link #goDown()} makes every call fail with a {@link HotRodClientException}, as an
 *       unreachable server would.</li>
 * </ul>
 * Anything else - a plain {@code put} or {@code replace} included - throws
 * {@link UnsupportedOperationException}: an unversioned bucket write is a bug, not something to fake.
 */
public final class InMemoryRemoteCache {

    private record Entry(byte[] value, long version, long created, long lifespanMillis) {
        boolean expiredAt(long now) {
            return lifespanMillis > 0 && now >= created + lifespanMillis;
        }
    }

    private final String name;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();
    private final AtomicBoolean down = new AtomicBoolean();

    public InMemoryRemoteCache(String name, Clock clock) {
        this.name = name;
        this.clock = clock;
    }

    /** The cache as the limiter sees it. */
    public RemoteCache<String, byte[]> asRemoteCache() {
        return view(false);
    }

    /** Every call fails from now on, until {@link #comeBack()}. */
    public void goDown() {
        down.set(true);
    }

    public void comeBack() {
        down.set(false);
    }

    @SuppressWarnings("unchecked")
    private RemoteCache<String, byte[]> view(boolean forceReturnValue) {
        InvocationHandler handler = (proxy, method, args) -> invoke(proxy, method, args, forceReturnValue);
        return (RemoteCache<String, byte[]>) Proxy.newProxyInstance(
                RemoteCache.class.getClassLoader(), new Class<?>[]{RemoteCache.class}, handler);
    }

    private Object invoke(Object proxy, Method method, Object[] arguments, boolean forceReturnValue) {
        Object[] args = arguments == null ? new Object[0] : arguments;
        int arity = args.length;
        switch (method.getName() + "/" + arity) {
            case "toString/0":
                return "InMemoryRemoteCache[" + name + "]";
            case "hashCode/0":
                return System.identityHashCode(proxy);
            case "equals/1":
                return proxy == args[0];
            case "getName/0":
                return name;
            default:
                break;
        }
        if (down.get()) {
            throw new HotRodClientException("Simulated outage of " + name);
        }
        return switch (method.getName() + "/" + arity) {
            case "withFlags/1" -> view(forceReturnValue
                    || Arrays.asList((Flag[]) args[0]).contains(Flag.FORCE_RETURN_VALUE));
            case "getWithMetadata/1" -> getWithMetadata((String) args[0]);
            case "containsKey/1" -> live((String) args[0]) != null;
            case "putIfAbsent/4" -> {
                byte[] previous = putIfAbsent((String) args[0], (byte[]) args[1],
                        ((TimeUnit) args[3]).toMillis((Long) args[2]));
                yield forceReturnValue ? previous : null;
            }
            case "replaceWithVersion/7" -> replaceWithVersion((String) args[0], (byte[]) args[1], (Long) args[2],
                    ((TimeUnit) args[4]).toMillis((Long) args[3]));
            case "remove/1" -> {
                Entry removed = entries.remove((String) args[0]);
                yield removed == null || removed.expiredAt(clock.millis()) ? null : removed.value();
            }
            case "clear/0" -> {
                entries.clear();
                yield null;
            }
            case "size/0" -> (int) entries.values().stream().filter(e -> !e.expiredAt(clock.millis())).count();
            default -> throw new UnsupportedOperationException(
                    "InMemoryRemoteCache does not fake " + method + " - add it if the limiter needs it");
        };
    }

    private Entry live(String key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expiredAt(clock.millis())) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    private MetadataValue<byte[]> getWithMetadata(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return null;
        }
        int lifespanSeconds = entry.lifespanMillis() > 0 ? (int) (entry.lifespanMillis() / 1000) : -1;
        return new MetadataValueImpl<>(entry.created(), lifespanSeconds, -1, -1, entry.version(), entry.value());
    }

    private byte[] putIfAbsent(String key, byte[] value, long lifespanMillis) {
        long now = clock.millis();
        Entry[] previous = new Entry[1];
        entries.compute(key, (k, current) -> {
            if (current != null && !current.expiredAt(now)) {
                previous[0] = current;
                return current;
            }
            return new Entry(value, versions.incrementAndGet(), now, lifespanMillis);
        });
        return previous[0] == null ? null : previous[0].value();
    }

    private boolean replaceWithVersion(String key, byte[] value, long version, long lifespanMillis) {
        long now = clock.millis();
        boolean[] replaced = new boolean[1];
        entries.computeIfPresent(key, (k, current) -> {
            if (current.expiredAt(now)) {
                return null;
            }
            if (current.version() != version) {
                return current;
            }
            replaced[0] = true;
            return new Entry(value, versions.incrementAndGet(), now, lifespanMillis);
        });
        return replaced[0];
    }
}
