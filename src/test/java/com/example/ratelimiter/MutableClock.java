package com.example.ratelimiter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock the tests move by hand, so refill periods can be crossed without waiting for them.
 * Starts at a fixed instant rather than 0 so the values look like the epoch millis they are.
 */
public class MutableClock extends Clock {

    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private final AtomicLong millis = new AtomicLong(START);
    private final ZoneId zone;

    public MutableClock() {
        this(ZoneId.of("UTC"));
    }

    private MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration amount) {
        millis.addAndGet(amount.toMillis());
    }

    /** Moves the clock backwards, as an NTP correction would. */
    public void rewind(Duration amount) {
        millis.addAndGet(-amount.toMillis());
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(newZone);
    }
}
