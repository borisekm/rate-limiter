package com.example.ratelimiter.config;

import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.spring.starter.remote.InfinispanRemoteCacheCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The {@code RemoteCacheManager} itself comes from the Infinispan remote starter, configured by
 * {@code infinispan.remote.*} in application.yml. What this adds to it is not per environment: the
 * buckets cache definition.
 */
@Configuration
public class HotRodConfig {

    private static final Logger log = LoggerFactory.getLogger(HotRodConfig.class);

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

    /**
     * Applied by the starter after {@code infinispan.remote.*}, just before it builds the client.
     *
     * @param hostnameValidation {@code ratelimiter.tls-hostname-validation}. The starter has no key for
     *                           it, so it is set here. Off, the client no longer checks the server's
     *                           certificate against {@code infinispan.remote.sni-host-name} (which may
     *                           then be left unset): any certificate the trust store accepts is
     *                           accepted for any server. An escape hatch, not a setting.
     */
    @Bean
    InfinispanRemoteCacheCustomizer bucketsCacheCustomizer(
            @Value("${ratelimiter.tls-hostname-validation:true}") boolean hostnameValidation) {
        return builder -> {
            addBucketsCache(builder);
            // hostnameValidation(...) also switches TLS on, so only touch it where TLS already is.
            if (!hostnameValidation && builder.build(false).security().ssl().enabled()) {
                log.warn("TLS hostname validation towards the bucket store is OFF "
                        + "(ratelimiter.tls-hostname-validation=false); set infinispan.remote.sni-host-name instead");
                builder.security().ssl().hostnameValidation(false);
            }
        };
    }

    /**
     * Public so the tests' clients create the cache exactly the way the app does. The marshaller is
     * pinned per cache because the starter's default is Java serialization, which would store opaque
     * Java-serialized blobs in a ProtoStream cache.
     */
    public static void addBucketsCache(ConfigurationBuilder builder) {
        builder.remoteCache(BUCKETS_CACHE)
                .configuration(BUCKETS_CACHE_DEFINITION)
                .marshaller(ProtoStreamMarshaller.class);
    }
}
