package com.example.ratelimiter.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Single default policy for now. Grows into a map of named policies later.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(
        @Positive long capacity,
        @Min(0) double refillPerSecond,
        @Positive long bucketMaxIdleMillis) {
}
