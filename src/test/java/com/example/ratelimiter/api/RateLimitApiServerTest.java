package com.example.ratelimiter.api;

import com.example.ratelimiter.HotRodTestServer;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link RateLimitApiContract} with the starter's own client against the shared test server, which also
 * proves {@code infinispan.remote.*} binds. Skipped where there is no Docker.
 */
@EnabledIf("com.example.ratelimiter.HotRodTestServer#dockerAvailable")
class RateLimitApiServerTest extends RateLimitApiContract {

    @DynamicPropertySource
    static void infinispan(DynamicPropertyRegistry registry) {
        registry.add("infinispan.remote.server-list", HotRodTestServer::address);
        registry.add("infinispan.remote.auth-username", () -> HotRodTestServer.USERNAME);
        registry.add("infinispan.remote.auth-password", () -> HotRodTestServer.PASSWORD);
    }
}
