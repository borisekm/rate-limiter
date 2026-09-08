package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One policy per limited {@link Resource}. Buckets are named {@code <resource>@<identifier>}, so a
 * resource's policy applies to every identifier independently.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(Map<Resource, @Valid ResourcePolicy> resources) {

    /**
     * @param capacity     max tokens the bucket holds (max burst); a fresh bucket starts full
     * @param refillTokens tokens added at the end of every refill period
     * @param refillPeriod how often {@code refillTokens} are added
     */
    public record ResourcePolicy(
            @Positive long capacity,
            @Positive long refillTokens,
            @NotNull Duration refillPeriod) {

        /** Time for an empty bucket to become full again. */
        public Duration timeToFull() {
            long periods = (capacity + refillTokens - 1) / refillTokens;
            return refillPeriod.multipliedBy(periods);
        }
    }

    /** Every resource needs a policy; an omission fails the startup bind rather than a request. */
    public RateLimiterProperties {
        Map<Resource, ResourcePolicy> configured =
                resources == null ? Map.of() : new EnumMap<>(resources);
        resources = configured;
        List<Resource> missing = Arrays.stream(Resource.values())
                .filter(r -> !configured.containsKey(r))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing ratelimiter.resources entries for: " + missing);
        }
    }

    public ResourcePolicy policyFor(Resource resource) {
        return resources.get(resource);
    }

    /**
     * How long a bucket must be kept. Once an empty bucket would have refilled to capacity it is
     * indistinguishable from an absent one, so anything older can be dropped.
     *
     * <p>Applied as lifespan rather than max-idle: Infinispan refuses max-idle alongside a store
     * without passivation (ISPN000651), and every check writes the entry anyway, so lifespan is
     * refreshed on each use and amounts to the same thing here.
     */
    public Duration bucketLifespan() {
        return resources.values().stream()
                .map(ResourcePolicy::timeToFull)
                .max(Duration::compareTo)
                .orElse(Duration.ofMinutes(1));
    }
}
