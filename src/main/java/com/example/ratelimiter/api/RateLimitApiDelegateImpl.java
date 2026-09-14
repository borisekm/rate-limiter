package com.example.ratelimiter.api;

import com.example.ratelimiter.api.model.CheckRateRequest;
import com.example.ratelimiter.api.model.CheckRateResponse;
import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.core.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Implementation of the generated delegate. The generated RateLimitApiController picks this
 * bean up automatically.
 */
@Component
public class RateLimitApiDelegateImpl implements RateLimitApiDelegate {

    /**
     * Which node answered. A header rather than a response field: the response schema belongs to
     * the API contract, and this is an operational detail - with buckets shared across the cluster
     * every node answers identically, so it says where the request landed, not what the answer is.
     */
    static final String SERVED_BY_HEADER = "X-Served-By";

    private final RateLimiter rateLimiter;

    /** Pod name on Kubernetes (the StatefulSet passes it in), hostname anywhere else. */
    private final String instanceId;

    public RateLimitApiDelegateImpl(
            RateLimiter rateLimiter,
            @Value("${ratelimiter.instance-id:}") String instanceId) {
        this.rateLimiter = rateLimiter;
        this.instanceId = instanceId.isBlank() ? localHostName() : instanceId;
    }

    @Override
    public ResponseEntity<CheckRateResponse> checkRate(CheckRateRequest request) {
        Resource resource = Resource.fromValue(request.getResource().getValue());
        Decision decision = rateLimiter.check(resource, request.getIdentifier());

        CheckRateResponse body = new CheckRateResponse()
                .allowed(decision.allowed())
                .limit(decision.limit())
                .remaining(decision.remaining())
                .retryAfterMillis(decision.retryAfterMillis());

        if (decision.allowed()) {
            return ResponseEntity.ok()
                    .header(SERVED_BY_HEADER, instanceId)
                    .body(body);
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(SERVED_BY_HEADER, instanceId)
                .header("Retry-After", String.valueOf(Math.max(1, decision.retryAfterMillis() / 1000)))
                .body(body);
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
