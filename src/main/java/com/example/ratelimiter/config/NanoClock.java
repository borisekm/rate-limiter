package com.example.ratelimiter.config;

/** Monotonic time source, abstracted so tests can control it. */
@FunctionalInterface
public interface NanoClock {
    long nanos();

    static NanoClock system() {
        return System::nanoTime;
    }
}
