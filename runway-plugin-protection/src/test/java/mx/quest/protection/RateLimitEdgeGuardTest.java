package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.protect.EdgeRequest;
import mx.quest.runway.protect.EdgeVerdict;
import mx.quest.runway.protect.RejectionOutcome;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Allowlist passes, a blocklisted IP is 403 (before the bucket), and an exhausted bucket is 429 with a Retry-After. */
class RateLimitEdgeGuardTest {

    @TempDir
    Path dir;

    private static EdgeRequest from(String ip) {
        return new EdgeRequest(ip, "GET", "/");
    }

    private IpBlocklist emptyBlocklist(CidrSet allowlist) {
        return new IpBlocklist(dir.resolve("blocklist.yml"), new YamlStore(), allowlist, () -> 1_000L);
    }

    private TokenBucketRateLimiter limiter(int capacity, double refill) {
        return new TokenBucketRateLimiter(capacity, refill, 10, () -> 0L);
    }

    @Test
    void anAllowlistedIpPassesWithoutConsumingTheBucket() {
        CidrSet allow = CidrSet.of(List.of("10.0.0.0/8"));
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, emptyBlocklist(allow), limiter(1, 0.0));
        assertTrue(guard.check(from("10.1.2.3")).allowed());
        assertTrue(guard.check(from("10.1.2.3")).allowed(), "allowlist short-circuits before the bucket");
    }

    /**
     * Loopback is NOT exempt from the rate limit: behind a same-host proxy whose
     * forwarded address is not trusted, every client arrives as {@code 127.0.0.1}, and an implicit exemption would
     * switch the limit off for the whole internet. What loopback does get is never being BLOCKED, so its throttles
     * never escalate — the safety rail against auto-blocking the operator's own proxy or probe.
     */
    @Test
    void loopbackIsRateLimitedButNeverEscalatesIntoABlock() {
        CidrSet allow = CidrSet.of(List.of());
        IpBlocklist blocklist = emptyBlocklist(allow);
        TokenBucketRateLimiter escalating = new TokenBucketRateLimiter(2, 0.0, 10, () -> 0L, blocklist,
                new TokenBucketRateLimiter.AutoBlock(true, 1, 60, 900), () -> 1_000L);
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, blocklist, escalating);
        for (String loopback : List.of("127.0.0.1", "::1")) {
            assertTrue(guard.check(from(loopback)).allowed());
            assertTrue(guard.check(from(loopback)).allowed());
            EdgeVerdict throttled = guard.check(from(loopback));
            assertFalse(throttled.allowed(), loopback + " must be rate-limited like any client");
            assertEquals(429, throttled.status());
            assertEquals(1, throttled.retryAfterSeconds(), "a throttle, not an auto-block's remaining time");
            assertEquals(null, blocklist.match(loopback), loopback + " must never be auto-blocked");
        }
        // The control: the same breach from a routable address DOES escalate (threshold 1), so the absence above is
        // the loopback rail, not a disabled escalation.
        guard.check(from("203.0.113.5"));
        guard.check(from("203.0.113.5"));
        guard.check(from("203.0.113.5"));
        assertEquals("blocklist-auto", blocklist.match("203.0.113.5").kind());
    }

    /** Listing loopback is how an operator exempts a same-host probe — it then passes before the bucket. */
    @Test
    void loopbackIsExemptOnlyWhenListed() {
        CidrSet allow = CidrSet.of(List.of("127.0.0.1/32"));
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, emptyBlocklist(allow), limiter(1, 0.0));
        assertTrue(guard.check(from("127.0.0.1")).allowed());
        assertTrue(guard.check(from("127.0.0.1")).allowed(), "listed: never throttled");
        assertTrue(guard.check(from("::1")).allowed());
        assertFalse(guard.check(from("::1")).allowed(), "only what is listed is exempt");
    }

    @Test
    void aBlocklistedIpIsForbiddenBeforeTheBucket() {
        CidrSet allow = CidrSet.of(List.of());
        IpBlocklist blocklist = emptyBlocklist(allow);
        blocklist.block("203.0.113.7", 900, "manual", true);
        // A bucket of capacity 0-effective would still 429; prove the 403 wins (checked first, no token consumed).
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, blocklist, limiter(100, 1.0));
        EdgeVerdict verdict = guard.check(from("203.0.113.7"));
        assertFalse(verdict.allowed());
        assertEquals(403, verdict.status());
    }

    @Test
    void exceedingTheRateLimitIs429WithRetryAfter() {
        CidrSet allow = CidrSet.of(List.of());
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, emptyBlocklist(allow), limiter(2, 0.0));
        assertTrue(guard.check(from("1.2.3.4")).allowed());
        assertTrue(guard.check(from("1.2.3.4")).allowed());
        EdgeVerdict verdict = guard.check(from("1.2.3.4"));
        assertFalse(verdict.allowed());
        assertEquals(429, verdict.status());
        assertEquals(1, verdict.retryAfterSeconds());
    }

    // ---- an auto-block is back-pressure, not a ban -----------------------------------------------

    /**
     * The case that costs indexing: an auto-escalated block answers {@code 429} with the real remaining time.
     *
     * <p>The clock is pinned at 1000, so a 900 s block lifts at 1900 and the header must say 900 — not a
     * constant, not the configured duration, but what is actually left. A crawler that is told "forbidden"
     * with no retry time may stop coming back for what is a fifteen-minute time-out.
     */
    @Test
    void anAutoEscalatedBlockIs429WithTheRemainingTime() {
        CidrSet allow = CidrSet.of(List.of());
        IpBlocklist blocklist = emptyBlocklist(allow);
        blocklist.block("198.51.100.9", 900, "auto: 60 breaches", false);

        EdgeVerdict verdict =
                new RateLimitEdgeGuard(allow, blocklist, limiter(100, 1.0)).check(from("198.51.100.9"));

        assertEquals(429, verdict.status(), "an auto-block is the rate limiter escalating, not a policy ban");
        assertEquals(900, verdict.retryAfterSeconds());
        assertEquals("blocklist-auto", verdict.reason(), "the metric/log tag must still say which kind it was");
    }

    /**
     * An operator ban stays {@code 403} with no retry time.
     *
     * <p>This is the half that keeps the change honest: {@code 429} is not "nicer", it is a specific claim
     * that the caller may return shortly. For a deliberate ban that claim would be false.
     */
    @Test
    void anOperatorBanStays403WithNoRetryTime() {
        CidrSet allow = CidrSet.of(List.of());
        IpBlocklist blocklist = emptyBlocklist(allow);
        blocklist.block("203.0.113.7", 900, "manual", true);

        EdgeVerdict verdict =
                new RateLimitEdgeGuard(allow, blocklist, limiter(100, 1.0)).check(from("203.0.113.7"));

        assertEquals(403, verdict.status());
        assertEquals(0, verdict.retryAfterSeconds(), "there is no honest retry time for a deliberate ban");
    }

    /** An indefinite auto-block has no expiry to report, so it falls back to 403 rather than inventing one. */
    @Test
    void anIndefiniteAutoBlockFallsBackTo403() {
        CidrSet allow = CidrSet.of(List.of());
        IpBlocklist blocklist = emptyBlocklist(allow);
        blocklist.block("198.51.100.10", 0, "auto, no expiry", false);

        EdgeVerdict verdict =
                new RateLimitEdgeGuard(allow, blocklist, limiter(100, 1.0)).check(from("198.51.100.10"));

        assertEquals(403, verdict.status());
        assertEquals(0, verdict.retryAfterSeconds());
    }

    /**
     * The remaining time counts down — it is read from the block, not from the configured duration.
     *
     * <p>A fixed {@code Retry-After} equal to the block length would look right in every single-instant test
     * and would keep telling a crawler to wait the full fifteen minutes one second before the block lifts.
     */
    @Test
    void theRetryTimeCountsDownAsTheBlockRunsOut() {
        CidrSet allow = CidrSet.of(List.of());
        long[] now = {1_000L};
        IpBlocklist blocklist =
                new IpBlocklist(dir.resolve("b.yml"), new YamlStore(), allow, () -> now[0]);
        blocklist.block("198.51.100.11", 900, "auto", false);
        RateLimitEdgeGuard guard = new RateLimitEdgeGuard(allow, blocklist, limiter(100, 1.0));

        now[0] = 1_800L;
        assertEquals(100, guard.check(from("198.51.100.11")).retryAfterSeconds());

        now[0] = 1_899L;
        assertEquals(1, guard.check(from("198.51.100.11")).retryAfterSeconds(), "never 0 while still blocked");

        now[0] = 1_901L;
        assertTrue(guard.check(from("198.51.100.11")).allowed(), "and then the block is simply over");
    }

    /**
     * Every rejection this guard can produce is declared, and nothing it cannot.
     *
     * <p>Written as an exact set rather than a containment check: the risk runs in both directions. A missing
     * pair leaves that series absent until first use — the defect this fixes — and a spurious pair publishes a
     * permanent zero for a response no code path produces, which reads as evidence that it never happens.
     *
     * <p>{@code blocklist-auto} legitimately appears under both statuses (429 with a remaining time, 403 when
     * the block is indefinite); the three tests above pin that both are reachable.
     */
    @Test
    void theGuardDeclaresExactlyTheRejectionsItCanProduce() {
        CidrSet allow = CidrSet.of(List.of());
        var declared = new RateLimitEdgeGuard(allow, emptyBlocklist(allow), limiter(1, 0.0)).declaredOutcomes();

        assertEquals(java.util.Set.of(
                new RejectionOutcome(429, "rate-limit"),
                new RejectionOutcome(429, "blocklist-auto"),
                new RejectionOutcome(403, "blocklist-auto"),
                new RejectionOutcome(403, "blocklist-manual"),
                new RejectionOutcome(403, "blocklist-cidr")), declared);
    }
}
