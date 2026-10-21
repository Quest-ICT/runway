package mx.quest.protection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-key (per-IP) token-bucket rate limiter with <b>auto-escalation</b>: each key gets a bucket of
 * {@code capacity} tokens that refills at {@code refillPerSecond}; a request consumes one token and is throttled
 * (rejected) when the bucket is empty. When a key accumulates {@code breachThreshold} throttled requests within
 * {@code windowSeconds}, it is handed to the {@link IpBlocklist} for a temporary <b>auto</b> block
 * ({@code blockSeconds}) — an ephemeral, in-memory ban that a still-abusive IP re-earns after a restart.
 *
 * <p>Bounded: once {@code maxKeys} distinct keys are tracked the table is cleared (a crude but memory-safe bound —
 * a flood across many IPs briefly resets buckets and offender stats rather than exhausting heap; the rich eviction
 * policy is a later refinement). Thread-safe (a lock-free map on the hot path; one short {@code synchronized} block
 * per bucket). The clock is injectable so the behaviour is deterministically testable.
 *
 * <h2>Offenders</h2>
 * Each bucket also tallies its total requests, its throttled (429) count and its last-seen time; {@link
 * #topOffenders(int)} returns the busiest rate-limited IPs (by throttled count) for the operator's
 * {@code /__app/dos} screen.
 *
 * <p><b>On the bound's precision:</b> the size-check and the {@code computeIfAbsent} in {@link #tryAcquire} are not
 * one atomic step, so under heavy concurrency the table may briefly hold up to ~thread-count entries beyond
 * {@code maxKeys} before the next call clears it. This is intentional: the map stays lock-free on the request hot
 * path and the overage is tiny and self-correcting, so memory is still effectively bounded.
 */
public final class TokenBucketRateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final LongSupplier nanoTime;

    // --- auto-escalation (all zero/absent ⇒ escalation is off; the rate-limit floor still applies) ---
    private final boolean autoBlockEnabled;
    private final int breachThreshold;
    private final long breachWindowMillis;
    private final long blockSeconds;
    private final IpBlocklist blocklist;
    private final LongSupplier milliTime;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * A plain rate limiter with <b>no</b> auto-escalation (the pre-1d floor: throttle only). Kept so the guard and
     * tests can exercise the token bucket in isolation.
     */
    public TokenBucketRateLimiter(int capacity, double refillPerSecond, int maxKeys, LongSupplier nanoTime) {
        this(capacity, refillPerSecond, maxKeys, nanoTime, null, new AutoBlock(false, 0, 0, 0), () -> 0L);
    }

    /**
     * The full limiter with auto-escalation into {@code blocklist}. When {@code autoBlock.enabled()} and a key
     * breaches {@code breachThreshold} times within {@code windowSeconds}, it is auto-blocked for
     * {@code blockSeconds}. Passing a {@code null} blocklist or a disabled {@link AutoBlock} turns escalation off.
     *
     * @param nanoTime  the monotonic nanosecond clock (token refill)
     * @param blocklist the escalation target (may be {@code null} to disable escalation)
     * @param autoBlock the escalation thresholds
     * @param milliTime the wall-clock millisecond clock (breach window + last-seen)
     */
    public TokenBucketRateLimiter(int capacity, double refillPerSecond, int maxKeys, LongSupplier nanoTime,
                                  IpBlocklist blocklist, AutoBlock autoBlock, LongSupplier milliTime) {
        this.capacity = Math.max(1, capacity);
        this.refillPerSecond = Math.max(0.0, refillPerSecond);
        this.maxKeys = Math.max(1, maxKeys);
        this.nanoTime = nanoTime;
        this.blocklist = blocklist;
        this.autoBlockEnabled = autoBlock != null && autoBlock.enabled() && blocklist != null;
        this.breachThreshold = autoBlock != null ? Math.max(1, autoBlock.breachThreshold()) : 1;
        this.breachWindowMillis = autoBlock != null ? Math.max(1, autoBlock.windowSeconds()) * 1000L : 1000L;
        this.blockSeconds = autoBlock != null ? Math.max(1, autoBlock.blockSeconds()) : 1;
        this.milliTime = milliTime;
    }

    /**
     * Consumes a token for {@code key}; returns {@code true} if one was available (allowed), else {@code false}
     * (throttled). A throttle feeds the auto-escalation window; the {@code breachThreshold}-th throttle within the
     * window hands {@code key} to the blocklist for a temporary auto block.
     */
    public boolean tryAcquire(String key) {
        if (buckets.size() > maxKeys) {
            buckets.clear();
        }
        long now = nanoTime.getAsLong();
        long nowMillis = milliTime.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        boolean autoBlock;
        boolean allowed;
        synchronized (bucket) {
            allowed = bucket.tryAcquire(refillPerSecond, capacity, now, nowMillis);
            autoBlock = !allowed && autoBlockEnabled && bucket.recordBreach(nowMillis, breachWindowMillis, breachThreshold);
        }
        if (autoBlock) {
            // Outside the bucket lock: the blocklist has its own synchronization and may touch disk (never for auto).
            blocklist.block(key, blockSeconds, "auto: rate-limit", false);
        }
        return allowed;
    }

    /**
     * The busiest rate-limited IPs — those with at least one throttled request — ordered by throttled count
     * (descending), capped at {@code limit}. A snapshot; safe to render.
     */
    public List<Offender> topOffenders(int limit) {
        List<Offender> out = new ArrayList<>();
        buckets.forEach((ip, bucket) -> {
            synchronized (bucket) {
                if (bucket.throttled > 0) {
                    out.add(new Offender(ip, bucket.requests, bucket.throttled, bucket.lastSeenMillis));
                }
            }
        });
        out.sort(Comparator.comparingLong(Offender::throttled).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(0, limit))) : out;
    }

    /**
     * The auto-escalation thresholds: an IP {@code breachThreshold} times throttled within {@code windowSeconds} is
     * auto-blocked for {@code blockSeconds}. {@code enabled == false} keeps the throttle floor without escalation.
     */
    public record AutoBlock(boolean enabled, int breachThreshold, long windowSeconds, long blockSeconds) {
    }

    /** A snapshot row for the offenders panel: the IP, its total requests, its throttled count, its last-seen ms. */
    public record Offender(String ip, long requests, long throttled, long lastSeenMillis) {
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;
        private long requests;
        private long throttled;
        private long lastSeenMillis;
        private final Deque<Long> breaches = new ArrayDeque<>(); // epoch millis of recent throttles, within window

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastNanos = now;
        }

        boolean tryAcquire(double refillPerSecond, int capacity, long now, long nowMillis) {
            double elapsedSeconds = Math.max(0.0, (now - lastNanos) / 1_000_000_000.0);
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastNanos = now;
            requests++;
            lastSeenMillis = nowMillis;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            throttled++;
            return false;
        }

        /**
         * Records a throttle at {@code nowMillis} into the sliding breach window and reports whether the count has
         * reached {@code threshold} (in which case the window is cleared so one block is emitted per breach-burst).
         * Must be called under this bucket's monitor.
         */
        boolean recordBreach(long nowMillis, long windowMillis, int threshold) {
            long cutoff = nowMillis - windowMillis;
            while (!breaches.isEmpty() && breaches.peekFirst() < cutoff) {
                breaches.pollFirst();
            }
            breaches.addLast(nowMillis);
            if (breaches.size() >= threshold) {
                breaches.clear();
                return true;
            }
            return false;
        }
    }
}
