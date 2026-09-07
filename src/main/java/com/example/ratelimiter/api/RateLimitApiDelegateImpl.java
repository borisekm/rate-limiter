package com.example.ratelimiter.api;

import com.example.ratelimiter.api.model.CheckRateRequest;
import com.example.ratelimiter.api.model.CheckRateResponse;
import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Implementation of the generated delegate. The generated RateLimitApiController picks this
 * bean up automatically.
 */
@Component
public class RateLimitApiDelegateImpl implements RateLimitApiDelegate {

    private final RateLimiter rateLimiter;

    public RateLimitApiDelegateImpl(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public ResponseEntity<CheckRateResponse> checkRate(CheckRateRequest request) {
        int tokens = request.getTokens() == null ? 1 : request.getTokens();
        Decision decision = rateLimiter.check(request.getKey(), tokens);

        CheckRateResponse body = new CheckRateResponse()
                .allowed(decision.allowed())
                .limit(decision.limit())
                .remaining(decision.remaining())
                .retryAfterMillis(decision.retryAfterMillis());

        if (decision.allowed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, decision.retryAfterMillis() / 1000)))
                .body(body);
    }
}
