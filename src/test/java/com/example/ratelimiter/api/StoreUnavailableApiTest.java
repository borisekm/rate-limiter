package com.example.ratelimiter.api;

import com.example.ratelimiter.HotRodTestServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The app with no reachable Infinispan server: it still starts, answers with the configured fallback
 * (here fail-open), marks each answer as degraded, and reports the store down in health - without
 * that taking readiness down with it.
 */
@SpringBootTest(properties = {
        "ratelimiter.when-store-unavailable=allow",
        "infinispan.remote.connect-timeout=500",
        "management.endpoint.health.probes.enabled=true"})
@AutoConfigureMockMvc
class StoreUnavailableApiTest {

    @DynamicPropertySource
    static void nothingListening(DynamicPropertyRegistry registry) {
        registry.add("infinispan.remote.server-list", HotRodTestServer::deadAddress);
    }

    @Autowired
    private MockMvc mockMvc;

    private ResultActions check(String identifier) throws Exception {
        return mockMvc.perform(post("/v1/rate/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resource\":\"subjectSearch\",\"identifier\":\"" + identifier + "\"}"));
    }

    @Test
    void failsOpenAndSaysSo() throws Exception {
        for (int i = 0; i < 20; i++) {                        // past capacity: nothing is counted
            check("10.0.0.7")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowed").value(true))
                    .andExpect(header().string("X-RateLimit-Degraded", "store-unavailable"));
        }
    }

    @Test
    void healthReportsTheStoreDownButReadinessStaysUp() throws Exception {
        mockMvc.perform(get("/actuator/health/bucketStore"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
