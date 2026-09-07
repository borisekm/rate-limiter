package com.example.ratelimiter.core;

public record Decision(boolean allowed, long limit, long remaining, long retryAfterMillis) {
}
