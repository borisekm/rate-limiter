package com.example.ratelimiter.core;

import java.util.Arrays;

/**
 * The limited resources, mirroring the {@code resource} enum in
 * {@code src/main/resources/openapi/rate-limiter-api.yaml}.
 *
 * <p>The OpenAPI generator emits its own nested {@code CheckRateRequest.ResourceEnum}; this is the
 * domain-side counterpart the limiter and the configuration are written against, so the core is not
 * coupled to generated code. Keep {@link #getValue()} in sync with the spec - {@link #fromValue}
 * is what the API layer crosses over with.
 *
 * <p>Each resource carries its own capacity and refill quota (see {@code ratelimiter.resources}) and
 * is applied per identifier: buckets are named {@code <resource>@<identifier>}.
 */
public enum Resource {

    /** Calls into the subject service. */
    SUBJECT_SEARCH("subjectSearch"),

    NEW_CASES("newCases"),

    /** Calls into our own service, counted per IP. */
    MAX_CALLS_IP("maxCallsIp"),

    /** Calls into our own service, counted per session. */
    MAX_CALLS_SESS("maxCallsSess");

    private final String value;

    Resource(String value) {
        this.value = value;
    }

    /** The wire value used in the API and as the configuration key. */
    public String getValue() {
        return value;
    }

    /** Accepts the wire value ("subjectSearch") as well as "subject-search" / "SUBJECT_SEARCH". */
    public static Resource fromValue(String value) {
        return Arrays.stream(values())
                .filter(r -> r.value.equalsIgnoreCase(value) || r.name().equalsIgnoreCase(normalize(value)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource '" + value + "'"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('-', '_');
    }

    @Override
    public String toString() {
        return value;
    }
}
