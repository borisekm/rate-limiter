package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One policy per limited {@link Resource}. Buckets are named {@code <resource>@<identifier>}, so a
 * resource's policy applies to every identifier independently.
 *
 * @param whenStoreUnavailable the answer given while the bucket store cannot be reached - there is no
 *                             default, every environment has to choose
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(
        Map<Resource, @Valid ResourcePolicy> resources,
        @NotNull WhenStoreUnavailable whenStoreUnavailable) {

    /**
     * What a check answers when the Infinispan server cannot be reached or refuses the request. Either
     * way the answer is flagged as degraded and a warning is logged; this only decides its direction.
     */
    public enum WhenStoreUnavailable {
        /** Fail open: let every call through, unlimited, until the store is back. */
        ALLOW,
        /** Fail closed: refuse every call until the store is back. */
        DENY
    }

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

    /** Fail-closed, for tests that only care about the policies. */
    public RateLimiterProperties(Map<Resource, ResourcePolicy> resources) {
        this(resources, WhenStoreUnavailable.DENY);
    }

    /** Every resource needs a policy; an omission fails the startup bind rather than a request. */
    @ConstructorBinding
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
     * <p>Applied as the lifespan of every write rather than as max-idle: every check writes the entry,
     * so lifespan is refreshed on each use and amounts to the same thing - without depending on how
     * the server's cache happens to be configured.
     */
    public Duration bucketLifespan() {
        return resources.values().stream()
                .map(ResourcePolicy::timeToFull)
                .max(Duration::compareTo)
                .orElse(Duration.ofMinutes(1));
    }
}
