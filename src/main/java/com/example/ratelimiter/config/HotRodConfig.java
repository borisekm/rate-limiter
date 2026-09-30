package com.example.ratelimiter.config;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class HotRodConfig {

    public static final String BUCKETS_CACHE = "rate-limit-buckets";

    /**
     * What the buckets cache is created as when the server does not have it yet; an existing cache is
     * used as it is. Two owners, so one Data Grid pod going away loses no bucket. No expiration here:
     * every write carries its own lifespan (see {@code RateLimiterProperties.bucketLifespan()}). Keys
     * are strings and values Bucket4j's own {@code byte[]}, both native to ProtoStream, so no schema
     * has to be registered on the server.
     */
    static final String BUCKETS_CACHE_DEFINITION = """
            {"distributed-cache": {
              "mode": "SYNC",
              "owners": 2,
              "statistics": true,
              "encoding": {"media-type": "application/x-protostream"}
            }}""";

    /**
     * Wall-clock time. Buckets outlive the JVM that wrote them and are read by other instances, so their
     * refill anchors cannot come from {@code nanoTime()}, whose zero point is per-JVM.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "stop")
    RemoteCacheManager remoteCacheManager(InfinispanClientProperties props) {
        return new RemoteCacheManager(clientConfiguration(props).build());
    }

    /** Public so the tests can connect to their in-JVM server exactly the way the app does. */
    public static ConfigurationBuilder clientConfiguration(InfinispanClientProperties props) {
        ConfigurationBuilder builder = new ConfigurationBuilder()
                .addServers(props.servers())
                .clientIntelligence(props.intelligence())
                .connectionTimeout((int) props.connectTimeout().toMillis())
                .socketTimeout((int) props.socketTimeout().toMillis());
        builder.remoteCache(BUCKETS_CACHE).configuration(BUCKETS_CACHE_DEFINITION);

        if (props.authenticated()) {
            var auth = builder.security().authentication().enable()
                    .username(props.username())
                    .password(props.password());
            if (props.saslMechanism() != null && !props.saslMechanism().isBlank()) {
                auth.saslMechanism(props.saslMechanism());
            }
        }

        InfinispanClientProperties.Tls tls = props.tls();
        if (tls.enabled()) {
            var ssl = builder.security().ssl().enable()
                    .sniHostName(tls.sniHostName() != null && !tls.sniHostName().isBlank()
                            ? tls.sniHostName()
                            : props.firstServerHost());
            if (tls.trustStore() != null && !tls.trustStore().isBlank()) {
                ssl.trustStoreFileName(tls.trustStore()).trustStoreType(tls.trustStoreType());
            }
        }
        return builder;
    }
}
