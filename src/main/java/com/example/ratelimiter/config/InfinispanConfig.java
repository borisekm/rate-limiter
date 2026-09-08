package com.example.ratelimiter.config;

import com.example.ratelimiter.core.RateLimiterSchemaImpl;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.spring.starter.embedded.InfinispanCacheConfigurer;
import org.infinispan.spring.starter.embedded.InfinispanGlobalConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class InfinispanConfig {

    public static final String BUCKETS_CACHE = "rate-limit-buckets";

    /** Nodes only cluster with nodes of the same name; keep environments apart with this. */
    private final String clusterName;

    public InfinispanConfig(@Value("${ratelimiter.cluster-name:rate-limiter}") String clusterName) {
        this.clusterName = clusterName;
    }

    @Bean
    NanoClock nanoClock() {
        return NanoClock.system();
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
        builder.transport().clusterName(clusterName);
        // On Kubernetes switch the JGroups stack, e.g.
        // builder.transport().addProperty("configurationFile", "default-configs/default-jgroups-kubernetes.xml")
        // The Spring Boot starter otherwise leaves the user marshaller as JavaSerializationMarshaller,
        // which cannot marshal ConsumeTokens across nodes (ISPN000936, blocked by the deserialization
        // allow list). An explicitly supplied marshaller does not pick up addContextInitializer, so
        // the schema is registered on the instance itself.
        ProtoStreamMarshaller marshaller = new ProtoStreamMarshaller();
        marshaller.register(new RateLimiterSchemaImpl());
        builder.serialization().marshaller(marshaller);
        builder.cacheContainer().statistics(true);
        return builder::build;
    }

    @Bean
    InfinispanCacheConfigurer bucketsCacheConfigurer(RateLimiterProperties props) {
        return manager -> manager.defineConfiguration(BUCKETS_CACHE, new ConfigurationBuilder()
                .clustering().cacheMode(CacheMode.DIST_SYNC)
                .hash().numOwners(2)
                .expiration().maxIdle(props.maxBucketIdle().toMillis(), TimeUnit.MILLISECONDS)
                .statistics().enable()
                .build());
    }
}
