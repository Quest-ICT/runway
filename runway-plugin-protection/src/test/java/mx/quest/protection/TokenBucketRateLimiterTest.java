package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The token bucket admits up to capacity, refills over time, and isolates buckets per key. */
class TokenBucketRateLimiterTest {

    @Test
    void admitsUpToCapacityThenRejects() {
        long[] now = {0};
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1.0, 1000, () -> now[0]);
        assertTrue(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"), "the bucket is empty");
    }

    @Test
    void refillsOverTime() {
        long[] now = {0};
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0, 1000, () -> now[0]); // 1 token, 1/sec
        assertTrue(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"));
        now[0] = 1_000_000_000L; // +1 second
        assertTrue(limiter.tryAcquire("ip"), "one token refilled after a second");
    }

    @Test
    void bucketsAreIsolatedPerKey() {
        long[] now = {0};
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.0, 1000, () -> now[0]);
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"), "a different IP has its own bucket");
    }
}
