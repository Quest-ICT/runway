package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rate limiter's auto-escalation into the blocklist: an IP throttled {@code breachThreshold} times within the
 * window is auto-blocked; a loopback/allowlisted IP never is (the block is refused). Plus the offenders tally.
 */
class RateLimiterAutoEscalationTest {

    @TempDir
    Path dir;

    private final AtomicLong millis = new AtomicLong(0L);

    private IpBlocklist blocklist(CidrSet allowlist) {
        // The blocklist clock is in SECONDS; keep it fixed — we assert on membership, not on block expiry here.
        return new IpBlocklist(dir.resolve("blocklist.yml"), new YamlStore(), allowlist, () -> 0L);
    }

    /** A limiter with capacity 1 / no refill (so every request after the first throttles) and the given auto-block. */
    private TokenBucketRateLimiter limiter(IpBlocklist blocklist, int threshold, long windowSeconds) {
        return new TokenBucketRateLimiter(1, 0.0, 1_000, () -> 0L, blocklist,
                new TokenBucketRateLimiter.AutoBlock(true, threshold, windowSeconds, 900), millis::get);
    }

    @Test
    void anIpIsAutoBlockedAfterBreachThresholdThrottlesInTheWindow() {
        IpBlocklist blocklist = blocklist(CidrSet.of(List.of()));
        TokenBucketRateLimiter limiter = limiter(blocklist, 3, 60);

        assertTrue(limiter.tryAcquire("9.9.9.9"), "first request consumes the only token");
        assertFalse(blocklist.isBlocked("9.9.9.9"));
        // Three throttles reach the threshold and trigger the auto-block.
        assertFalse(limiter.tryAcquire("9.9.9.9")); // breach 1
        assertFalse(blocklist.isBlocked("9.9.9.9"));
        assertFalse(limiter.tryAcquire("9.9.9.9")); // breach 2
        assertFalse(blocklist.isBlocked("9.9.9.9"));
        assertFalse(limiter.tryAcquire("9.9.9.9")); // breach 3 => auto-block
        assertTrue(blocklist.isBlocked("9.9.9.9"), "the 3rd throttle in the window auto-blocks the IP");

        // The auto block was NOT persisted (in-memory only).
        assertFalse(blocklist.list().isEmpty());
        assertFalse(blocklist.list().get(0).manual(), "the auto block is not a manual (persisted) entry");
    }

    @Test
    void throttlesOutsideTheWindowDoNotAccumulateToABlock() {
        IpBlocklist blocklist = blocklist(CidrSet.of(List.of()));
        TokenBucketRateLimiter limiter = limiter(blocklist, 3, 60); // 60s window

        limiter.tryAcquire("8.8.8.8"); // token consumed
        limiter.tryAcquire("8.8.8.8"); // breach 1 at t=0
        millis.set(120_000L);          // +120s — the first breach falls out of the 60s window
        limiter.tryAcquire("8.8.8.8"); // breach 2 (only 1 in window after prune, so this makes 1)
        millis.set(121_000L);
        limiter.tryAcquire("8.8.8.8"); // still under threshold within the window
        assertFalse(blocklist.isBlocked("8.8.8.8"), "spread-out throttles never reach the threshold in one window");
    }

    @Test
    void escalationNeverBlocksAnAllowlistedOrLoopbackIp() {
        IpBlocklist blocklist = blocklist(CidrSet.of(List.of("10.0.0.0/8")));
        TokenBucketRateLimiter limiter = limiter(blocklist, 2, 60);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("10.1.2.3"); // hammer an allowlisted IP
        }
        assertFalse(blocklist.isBlocked("10.1.2.3"), "an allowlisted IP is never auto-blocked (block refused)");
    }

    @Test
    void offendersAreTrackedByThrottledCountDescending() {
        IpBlocklist blocklist = blocklist(CidrSet.of(List.of()));
        // High threshold so nothing auto-blocks — we only want the offenders tally here.
        TokenBucketRateLimiter limiter = limiter(blocklist, 1_000, 60);

        limiter.tryAcquire("1.1.1.1"); // token
        limiter.tryAcquire("1.1.1.1"); // throttled x1
        limiter.tryAcquire("2.2.2.2"); // token
        limiter.tryAcquire("2.2.2.2"); // throttled
        limiter.tryAcquire("2.2.2.2"); // throttled x2

        List<TokenBucketRateLimiter.Offender> top = limiter.topOffenders(10);
        assertEquals(2, top.size());
        assertEquals("2.2.2.2", top.get(0).ip(), "the busiest throttled IP is first");
        assertEquals(2, top.get(0).throttled());
        assertEquals("1.1.1.1", top.get(1).ip());
        assertEquals(1, top.get(1).throttled());
    }

    @Test
    void offendersOnlyIncludeThrottledIps() {
        IpBlocklist blocklist = blocklist(CidrSet.of(List.of()));
        TokenBucketRateLimiter limiter = limiter(blocklist, 1_000, 60);
        limiter.tryAcquire("3.3.3.3"); // one clean request, never throttled
        assertTrue(limiter.topOffenders(10).isEmpty(), "an IP that was never throttled is not an offender");
    }
}
