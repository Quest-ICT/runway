package mx.quest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The per-client courtesy limiter: a flood from one key is rejected; a disabled window never rejects; each window
 * restarts once it has passed; the table of clients is bounded.
 */
class SearchRateLimiterTest {

    private static SearchRateLimiter limiter(int perMinute, int perHour) {
        SearchProperties.RateLimit cfg = new SearchProperties.RateLimit();
        cfg.setPerIpPerMinute(perMinute);
        cfg.setPerIpPerHour(perHour);
        return new SearchRateLimiter(cfg);
    }

    @Test
    void aFloodFromOneIpIsRejectedOncePastTheCeiling() {
        SearchRateLimiter limiter = limiter(3, 1000);
        assertTrue(limiter.tryAcquire("1.2.3.4"), "1st query allowed");
        assertTrue(limiter.tryAcquire("1.2.3.4"), "2nd query allowed");
        assertTrue(limiter.tryAcquire("1.2.3.4"), "3rd query allowed (at the ceiling)");
        assertFalse(limiter.tryAcquire("1.2.3.4"), "4th query rejected — over the per-minute ceiling");
        assertFalse(limiter.tryAcquire("1.2.3.4"), "…and stays rejected");
    }

    @Test
    void limitsArePerKeyNotGlobal() {
        SearchRateLimiter limiter = limiter(1, 1000);
        assertTrue(limiter.tryAcquire("10.0.0.1"), "first IP's single allowance");
        assertFalse(limiter.tryAcquire("10.0.0.1"), "first IP is now over its limit");
        assertTrue(limiter.tryAcquire("10.0.0.2"), "a DIFFERENT IP has its own independent bucket");
    }

    @Test
    void theHourWindowAlsoRejects() {
        SearchRateLimiter limiter = limiter(1000, 2);
        assertTrue(limiter.tryAcquire("9.9.9.9"));
        assertTrue(limiter.tryAcquire("9.9.9.9"));
        assertFalse(limiter.tryAcquire("9.9.9.9"), "the per-hour ceiling rejects even under the minute ceiling");
    }

    @Test
    void aZeroCeilingDisablesTheWindow() {
        SearchRateLimiter limiter = limiter(0, 0); // both windows off
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire("5.5.5.5"), "a 0 ceiling means 'no limit' — never rejects");
        }
    }

    @Test
    void aBlankKeyAlwaysPasses() {
        SearchRateLimiter limiter = limiter(1, 1);
        assertTrue(limiter.tryAcquire(""), "a blank id has no bucket to count against");
        assertTrue(limiter.tryAcquire(null));
        assertTrue(limiter.tryAcquire(""), "…and still passes");
    }

    private static SearchRateLimiter limiter(int perMinute, int perHour, MutableClock clock, int maxKeys) {
        SearchProperties.RateLimit cfg = new SearchProperties.RateLimit();
        cfg.setPerIpPerMinute(perMinute);
        cfg.setPerIpPerHour(perHour);
        return new SearchRateLimiter(cfg, clock, maxKeys);
    }

    @Test
    void theMinuteWindowRestartsAfterAMinute() {
        MutableClock clock = new MutableClock();
        SearchRateLimiter limiter = limiter(2, 1000, clock, 100);
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertFalse(limiter.tryAcquire("1.1.1.1"), "over the minute ceiling");
        clock.advance(Duration.ofSeconds(59));
        assertFalse(limiter.tryAcquire("1.1.1.1"), "still the same minute window");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire("1.1.1.1"), "a minute after the window opened it restarts");
    }

    @Test
    void theHourWindowRestartsAfterAnHourButNotAfterAMinute() {
        MutableClock clock = new MutableClock();
        SearchRateLimiter limiter = limiter(1000, 2, clock, 100);
        assertTrue(limiter.tryAcquire("2.2.2.2"));
        assertTrue(limiter.tryAcquire("2.2.2.2"));
        clock.advance(Duration.ofMinutes(5));
        assertFalse(limiter.tryAcquire("2.2.2.2"), "the minute window restarted, the hour window did not");
        clock.advance(Duration.ofMinutes(55));
        assertTrue(limiter.tryAcquire("2.2.2.2"), "an hour after the window opened it restarts");
    }

    @Test
    void theClientTableIsBoundedDroppingExpiredClientsFirst() {
        MutableClock clock = new MutableClock();
        SearchRateLimiter limiter = limiter(1, 1000, clock, 3);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        clock.advance(Duration.ofHours(1));
        assertTrue(limiter.tryAcquire("c"));
        assertFalse(limiter.tryAcquire("c"), "c is limited");
        assertTrue(limiter.tryAcquire("d"), "a fourth client makes room by dropping the expired a and b");
        assertEquals(2, limiter.trackedClients(), "only the active clients c and d are kept");
        assertFalse(limiter.tryAcquire("c"), "an active client's window survives the clean-up");
    }

    @Test
    void aFullTableOfActiveClientsIsEmptiedRatherThanGrown() {
        MutableClock clock = new MutableClock();
        SearchRateLimiter limiter = limiter(1, 1000, clock, 2);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        assertTrue(limiter.tryAcquire("c"), "no client expired: the table restarts (fails open), it never grows");
        assertEquals(1, limiter.trackedClients());
        assertTrue(limiter.tryAcquire("a"), "a's window restarted with the table");
    }
}
