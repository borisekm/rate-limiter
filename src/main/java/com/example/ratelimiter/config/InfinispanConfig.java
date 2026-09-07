package com.example.ratelimiter.config;

import com.example.ratelimiter.core.RateLimiterSchemaImpl;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.spring.starter.embedded.InfinispanCacheConfigurer;
import org.infinispan.spring.starter.embedded.InfinispanGlobalConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class InfinispanConfig {

    public static final String BUCKETS_CACHE = "rate-limit-buckets";

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
        builder.transport().clusterName("rate-limiter");
        // On Kubernetes switch the JGroups stack, e.g.
        // builder.transport().addProperty("configurationFile", "default-configs/default-jgroups-kubernetes.xml")
        builder.serialization().addContextInitializer(new RateLimiterSchemaImpl());
        builder.cacheContainer().statistics(true);
        return builder::build;
    }

    @Bean
    InfinispanCacheConfigurer bucketsCacheConfigurer(RateLimiterProperties props) {
        return manager -> manager.defineConfiguration(BUCKETS_CACHE, new ConfigurationBuilder()
                .clustering().cacheMode(CacheMode.DIST_SYNC)
                .hash().numOwners(2)
                .expiration().maxIdle(props.bucketMaxIdleMillis(), TimeUnit.MILLISECONDS)
                .statistics().enable()
                .build());
    }
}
