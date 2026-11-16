package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How {@code ratelimiter.resources.*} values, durations especially, come out of the configuration. */
class RateLimiterPropertiesBindingTest {

    /** Binds every resource with a default policy, then applies the overrides on top. */
    private static RateLimiterProperties bind(Map<String, String> overrides) {
        Map<String, String> source = new HashMap<>();
        source.put("ratelimiter.when-store-unavailable", "allow");
        for (Resource resource : Resource.values()) {
            String prefix = "ratelimiter.resources." + resource.getValue() + ".";
            source.put(prefix + "capacity", "10");
            source.put(prefix + "refill-tokens", "10");
            source.put(prefix + "refill-period", "60s");
        }
        source.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("ratelimiter", RateLimiterProperties.class).get();
    }

    @ParameterizedTest
    @CsvSource({
            "24h,   PT24H",
            "12h,   PT12H",
            "60s,   PT1M",
            "90s,   PT1M30S",
            "15m,   PT15M",
            "1d,    PT24H",
            "500ms, PT0.5S",
            "PT24H, PT24H",
            "PT2M30S, PT2M30S",
    })
    void durationSyntaxes(String text, String expected) {
        RateLimiterProperties properties =
                bind(Map.of("ratelimiter.resources.grustabfrage.refill-period", text));

        assertThat(properties.policyFor(Resource.fromValue("grustabfrage")).refillPeriod())
                .isEqualTo(Duration.parse(expected));
    }

    @Test
    void aBareNumberIsMilliseconds() {
        // No @DurationUnit on the record: "24" is 24 ms, not 24 hours. Always write the unit.
        assertThat(bind(Map.of("ratelimiter.resources.grustabfrage.refill-period", "150000"))
                .policyFor(Resource.fromValue("grustabfrage")).refillPeriod())
                .isEqualTo(Duration.ofMinutes(2).plusSeconds(30));
    }

    @Test
    void timeToFullRoundsUpToWholePeriods() {
        RateLimiterProperties properties = bind(Map.of(
                "ratelimiter.resources.grustabfrage.capacity", "25",
                "ratelimiter.resources.grustabfrage.refill-tokens", "10",
                "ratelimiter.resources.grustabfrage.refill-period", "24h"));

        assertThat(properties.policyFor(Resource.fromValue("grustabfrage")).timeToFull())
                .isEqualTo(Duration.ofHours(72));
    }

    /**
     * The real application.yml, with the placeholders resolved the way the environment variable would
     * resolve them. Fails if a default in the file is not what the comment above it promises.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(RateLimiterProperties.class)
    static class Config { }

    @Test
    void applicationYmlDefaults() {
        contextRunner.run(context -> {
            RateLimiterProperties properties = context.getBean(RateLimiterProperties.class);

            assertThat(properties.policyFor(Resource.fromValue("subjectSearch")).refillPeriod())
                    .isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.policyFor(Resource.fromValue("newCases")).refillPeriod())
                    .isEqualTo(Duration.ofHours(24));
            assertThat(properties.policyFor(Resource.fromValue("maxCallsIp")).refillPeriod())
                    .isEqualTo(Duration.ofHours(24));
            assertThat(properties.policyFor(Resource.fromValue("maxCallsSess")).refillPeriod())
                    .isEqualTo(Duration.ofHours(12));
            assertThat(properties.policyFor(Resource.fromValue("grustabfrage")).refillPeriod())
                    .isEqualTo(Duration.ofHours(24));
        });
    }

    @ParameterizedTest
    @CsvSource({"24h, PT24H", "12h, PT12H", "90m, PT1H30M", "PT24H, PT24H", "1d, PT24H"})
    void environmentVariableOverridesTheYmlPlaceholder(String value, String expected) {
        // The placeholder name is what the environment variable is called; withPropertyValues stands in for it.
        contextRunner
                .withPropertyValues("RATELIMITER_RESOURCES_GRUSTABFRAGE_REFILLPERIOD=" + value)
                .run(context -> assertThat(context.getBean(RateLimiterProperties.class)
                        .policyFor(Resource.fromValue("grustabfrage")).refillPeriod())
                        .isEqualTo(Duration.parse(expected)));
    }
}
