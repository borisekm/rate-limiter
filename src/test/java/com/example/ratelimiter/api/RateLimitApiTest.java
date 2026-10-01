package com.example.ratelimiter.api;

import com.example.ratelimiter.HotRodTestServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire contract, over the real context and the policies in application.yml. Time is not
 * controllable here, so this asserts the shape of {@code retryAfterMillis} - never 0, never past the
 * resource's period - while {@code RateLimiterTest} pins the arithmetic down with a fake clock.
 */
@SpringBootTest(properties = {
        // On Kubernetes the Deployment passes the pod name here; anywhere else it is the hostname.
        "ratelimiter.instance-id=test-node-1",
        // The container advertises its internal address, which the host may not reach.
        "infinispan.remote.client-intelligence=BASIC"})
@AutoConfigureMockMvc
class RateLimitApiTest {

    /** The shared test server; every test uses a fresh random identifier, so buckets never collide. */
    @DynamicPropertySource
    static void infinispan(DynamicPropertyRegistry registry) {
        registry.add("infinispan.remote.server-list", HotRodTestServer::address);
        registry.add("infinispan.remote.auth-username", () -> HotRodTestServer.USERNAME);
        registry.add("infinispan.remote.auth-password", () -> HotRodTestServer.PASSWORD);
    }

    private static final long MINUTE_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();

    @Autowired
    private MockMvc mockMvc;

    private ResultActions check(String resource, String identifier) throws Exception {
        return mockMvc.perform(post("/v1/rate/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resource\":\"" + resource + "\",\"identifier\":\"" + identifier + "\"}"));
    }

    private long retryAfterOf(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        return JsonPath.parse(json).read("$.retryAfterMillis", Long.class);
    }

    @Test
    void allowedCallReportsTimeToTheNextRefillRatherThanZero() throws Exception {
        ResultActions result = check("subjectSearch", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.limit").value(15))
                .andExpect(jsonPath("$.remaining").value(14));

        assertThat(retryAfterOf(result)).isBetween(1L, MINUTE_MILLIS);
    }

    @Test
    void retryAfterShrinksAsThePeriodElapses() throws Exception {
        String identifier = UUID.randomUUID().toString();
        long first = retryAfterOf(check("subjectSearch", identifier));
        long second = retryAfterOf(check("subjectSearch", identifier));

        // Same period, later in it - the countdown can only move towards the boundary.
        assertThat(second).isLessThanOrEqualTo(first).isPositive();
    }

    @Test
    void refusedCallCarriesRetryAfterInBodyAndHeader() throws Exception {
        String identifier = UUID.randomUUID().toString();
        for (int i = 0; i < 15; i++) {
            check("subjectSearch", identifier).andExpect(status().isOk());
        }

        ResultActions refused = check("subjectSearch", identifier)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-Served-By", "test-node-1"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.remaining").value(0));

        assertThat(retryAfterOf(refused)).isBetween(1L, MINUTE_MILLIS);
    }

    @Test
    void servedByHeaderNamesTheAnsweringNode() throws Exception {
        // Which node answered - the response body is the API contract's, so this rides in a header.
        check("subjectSearch", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(header().string("X-Served-By", "test-node-1"))
                .andExpect(header().doesNotExist("X-RateLimit-Degraded"));
    }

    @Test
    void bucketStoreHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/bucketStore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void dailyResourceReportsItsOwnPeriod() throws Exception {
        ResultActions result = check("maxCallsIp", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100000));

        assertThat(retryAfterOf(result)).isBetween(DAY_MILLIS - MINUTE_MILLIS, DAY_MILLIS);
    }
}
