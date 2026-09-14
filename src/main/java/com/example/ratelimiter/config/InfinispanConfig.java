package com.example.ratelimiter.config;

import io.github.bucket4j.grid.infinispan.serialization.Bucket4jProtobufContextInitializer;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.spring.starter.embedded.InfinispanCacheConfigurer;
import org.infinispan.spring.starter.embedded.InfinispanGlobalConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Configuration
public class InfinispanConfig {

    public static final String BUCKETS_CACHE = "rate-limit-buckets";

    /**
     * Subdirectory of the store, named after the format the bucket state is written in. Entries
     * persisted by an older format cannot be read back - the marshaller for them no longer exists -
     * and a store full of them fails every request that touches such a key with
     * "No marshaller registered for Protobuf type ...". Bumping this on a format change starts a
     * clean store instead; the old directory is then inert and can be deleted at leisure.
     */
    private static final String STATE_FORMAT = "bucket4j-v2";

    /** Nodes only cluster with nodes of the same name; keep environments apart with this. */
    private final String clusterName;

    /** Where this node keeps its bucket store. Must not be shared with another node on the host. */
    private final String persistenceLocation;

    private final boolean persistenceEnabled;

    /** JGroups stack. TCP everywhere; only member discovery differs between environments. */
    private final String jgroupsConfig;

    public InfinispanConfig(
            @Value("${ratelimiter.cluster-name:rate-limiter}") String clusterName,
            @Value("${ratelimiter.persistence.location:./data/rate-limiter}") String persistenceLocation,
            @Value("${ratelimiter.persistence.enabled:true}") boolean persistenceEnabled,
            @Value("${ratelimiter.jgroups-config:org/infinispan/configuration/default-jgroups-tcp.xml}") String jgroupsConfig) {
        this.clusterName = clusterName;
        this.persistenceLocation = persistenceLocation;
        this.persistenceEnabled = persistenceEnabled;
        this.jgroupsConfig = jgroupsConfig;
    }

    /**
     * Wall-clock time. Buckets outlive the JVM that wrote them and are read by other nodes, so their
     * refill anchors cannot come from {@code nanoTime()}, whose zero point is per-JVM.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Global (cache-manager level) configuration: clustered transport + ProtoStream schema
     * for our value/function types so entries and compute() functions can be shipped between nodes.
     * On a single node this simply forms a cluster of one.
     */
    @Bean
    InfinispanGlobalConfigurer globalConfigurer() {
        GlobalConfigurationBuilder builder = new GlobalConfigurationBuilder()
                .clusteredDefault();
        // TCP rather than the clusteredDefault() UDP stack: multicast is unavailable on OpenShift and
        // most cloud networks, so this is the transport we deploy on. The bundled TCP stack still
        // discovers members with MPING (multicast), which works locally but not on OpenShift - switch
        // ratelimiter.jgroups-config there, see README.
        builder.transport()
                .clusterName(clusterName)
                .addProperty("configurationFile", jgroupsConfig);
        // The Spring Boot starter otherwise leaves the user marshaller as JavaSerializationMarshaller,
        // which cannot marshal Bucket4j's entry processor across nodes (ISPN000936, blocked by the
        // deserialization allow list). With a ProtoStream user marshaller, Infinispan 16 stops routing
        // user objects through that marshaller at all (GlobalMarshaller.skipUserMarshaller) and writes
        // them with the global ProtoStream context instead - so Bucket4j's schema has to be a
        // configured context initializer, which lands in both the user and the global context.
        // Registering it on the marshaller instance alone (the Infinispan 15 arrangement) leaves the
        // global context without it, and every cross-node bucket operation dies with
        // "No marshaller registered for object of Java type ... InfinispanProcessor".
        builder.serialization()
                .marshaller(new ProtoStreamMarshaller())
                .addContextInitializer(new Bucket4jProtobufContextInitializer());
        builder.cacheContainer().statistics(true);
        if (persistenceEnabled) {
            // A file store resolves its relative paths against global state, which is off by default.
            builder.globalState().enable().persistentLocation(persistenceLocation);
        }
        return builder::build;
    }

    @Bean
    InfinispanCacheConfigurer bucketsCacheConfigurer(RateLimiterProperties props) {
        ConfigurationBuilder cache = new ConfigurationBuilder();
        cache.clustering().cacheMode(CacheMode.DIST_SYNC)
                .hash().numOwners(2)
                .expiration().lifespan(props.bucketLifespan().toMillis(), TimeUnit.MILLISECONDS)
                .statistics().enable();

        if (persistenceEnabled) {
            // Buckets survive a restart of every node. The store is this node's own (shared=false), so
            // it holds the segments this node owns; coming back with a different number of nodes can
            // therefore orphan buckets. See README for what that means on OpenShift.
            cache.persistence()
                    .passivation(false)          // write through to the store, do not move entries out of memory
                    .addSoftIndexFileStore()
                    // Relative: resolved against the global persistent location set above.
                    .dataLocation(STATE_FORMAT + "/data")
                    .indexLocation(STATE_FORMAT + "/index")
                    .segmented(true)
                    .shared(false)
                    .preload(false)              // compute() reads through on a miss; no full load at boot
                    .purgeOnStartup(false)       // the whole point
                    .async().enable();           // write-behind: a hard kill can lose the last few writes
        }
        return manager -> manager.defineConfiguration(BUCKETS_CACHE, cache.build());
    }
}
