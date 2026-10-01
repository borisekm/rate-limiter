package com.example.ratelimiter.config;

import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the customizer adds to the starter's client configuration. */
class HotRodConfigTest {

    private static Configuration customized(boolean hostnameValidation, boolean tls) {
        ConfigurationBuilder builder = new ConfigurationBuilder().addServers("localhost:11222");
        if (tls) {
            // As on OpenShift, minus the SNI host name - the ISPN004112 case the flag is for.
            builder.security().ssl().enable().trustStoreFileName("service-ca.crt").trustStoreType("PEM");
        }
        new HotRodConfig().bucketsCacheCustomizer(hostnameValidation).customize(builder);
        return builder.build();
    }

    @Test
    void addsTheBucketsCacheAndKeepsHostnameValidationByDefault() {
        Configuration configuration = customized(true, false);

        assertThat(configuration.remoteCaches()).containsKey(HotRodConfig.BUCKETS_CACHE);
        assertThat(configuration.security().ssl().hostnameValidation()).isTrue();
    }

    @Test
    void turnsHostnameValidationOffOverTlsWhenFlagged() {
        Configuration configuration = customized(false, true);   // would fail ISPN004112 with validation on

        assertThat(configuration.security().ssl().enabled()).isTrue();
        assertThat(configuration.security().ssl().hostnameValidation()).isFalse();
    }

    @Test
    void theFlagDoesNotTurnTlsOn() {
        Configuration configuration = customized(false, false);

        assertThat(configuration.security().ssl().enabled()).isFalse();
    }
}
