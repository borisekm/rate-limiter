package com.example.ratelimiter;

import com.example.ratelimiter.config.HotRodConfig;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Real Hot Rod clients pointed where nothing listens, for the tests of what an unreachable store does
 * to the limiter and the app - the one thing {@link InMemoryRemoteCache} cannot stand in for, since
 * there the exception comes from the client itself.
 */
public final class HotRodTestClient {

    private HotRodTestClient() {
    }

    /** A client for {@code servers}, with the buckets cache defined as the app defines it. */
    public static RemoteCacheManager client(String servers) {
        ConfigurationBuilder builder = new ConfigurationBuilder()
                .addServers(servers)
                .clientIntelligence(ClientIntelligence.BASIC)
                .connectionTimeout(1000)
                .socketTimeout(2000);
        builder.security().authentication().enable()
                .realm("default")
                .username("ratelimiter")
                .password("ratelimiter-test");
        HotRodConfig.addBucketsCache(builder);
        return new RemoteCacheManager(builder.build());
    }

    /** An address nothing listens on. */
    public static String deadAddress() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
