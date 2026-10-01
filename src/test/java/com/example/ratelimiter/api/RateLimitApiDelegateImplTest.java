package com.example.ratelimiter.api;

import com.example.ratelimiter.api.model.CheckRateRequest;
import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.core.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the context tests cannot vary: the instance id default and the Retry-After rounding. */
class RateLimitApiDelegateImplTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private ResponseEntity<?> check(RateLimitApiDelegateImpl delegate) {
        return delegate.checkRate(new CheckRateRequest()
                .resource(CheckRateRequest.ResourceEnum.SUBJECT_SEARCH)
                .identifier("ip"));
    }

    @Test
    void servedByDefaultsToTheHostName() throws Exception {
        when(rateLimiter.check(Resource.SUBJECT_SEARCH, "ip")).thenReturn(new Decision(true, 15, 14, 60_000));

        assertThat(check(new RateLimitApiDelegateImpl(rateLimiter, "")).getHeaders()
                .getFirst(RateLimitApiDelegateImpl.SERVED_BY_HEADER))
                .isEqualTo(InetAddress.getLocalHost().getHostName());
    }

    @Test
    void retryAfterHeaderIsAtLeastOneSecond() {
        when(rateLimiter.check(Resource.SUBJECT_SEARCH, "ip")).thenReturn(new Decision(false, 15, 0, 400));

        ResponseEntity<?> response = check(new RateLimitApiDelegateImpl(rateLimiter, "node"));
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
