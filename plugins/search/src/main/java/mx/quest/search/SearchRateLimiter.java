package mx.quest.search;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny per-client query limiter for the public JSON search API — the small in-plugin abuse guard that keeps a
 * query flood from hammering the index. Two fixed windows per client, one minute and one hour, each starting at the
 * client's first query in it; a window whose ceiling is {@code <= 0} is disabled.
 *
 * <h2>The key</h2>
 * The caller keys it with {@link SearchClientId#of}: the request's client IP <b>after the kernel's trusted-proxy
 * gate</b> ({@code RouteRequest.clientIp()}, SPI 0.5.0). A forwarded header is applied by the kernel only when the
 * immediate peer is a configured trusted proxy, so a visitor cannot pick a fresh key per request by sending a made-up
 * {@code X-Forwarded-For} (search 1.x keyed on that header's first hop and could be evaded that way). The kernel's
 * edge guard stays the real, platform-wide DoS floor beneath this courtesy limit.
 *
 * <h2>Bounded</h2>
 * At most {@link #MAX_KEYS} clients are tracked. When the table is full, clients whose windows have all expired are
 * dropped first; if every tracked client is still active, the table is emptied (every window restarts) — the limiter
 * fails open rather than growing without bound, which is acceptable for a courtesy limit with a floor beneath it.
 *
 * <p>Thread-safe.
 */
public class SearchRateLimiter {

    /** Bounds the number of distinct clients held, so the limiter itself can't be turned into a memory-growth vector. */
    static final int MAX_KEYS = 50_000;

    private static final long MINUTE = Duration.ofMinutes(1).toMillis();
    private static final long HOUR = Duration.ofHours(1).toMillis();

    private final int perMinute;
    private final int perHour;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Windows> clients = new ConcurrentHashMap<>();

    public SearchRateLimiter(SearchProperties.RateLimit config) {
        this(config, Clock.systemUTC(), MAX_KEYS);
    }

    /** Test seam: a controllable clock and table size. */
    SearchRateLimiter(SearchProperties.RateLimit config, Clock clock, int maxKeys) {
        this.perMinute = config.getPerIpPerMinute();
        this.perHour = config.getPerIpPerHour();
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /**
     * Records one query for {@code clientId} and reports whether it is within both windows. A blank id passes
     * (no key to count); a window whose ceiling is {@code <= 0} is disabled and never rejects.
     *
     * @return {@code true} to allow the query, {@code false} to reject it (the caller answers {@code 429})
     */
    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return true;
        }
        long now = clock.millis();
        if (clients.size() >= maxKeys && !clients.containsKey(clientId)) {
            makeRoom(now);
        }
        Windows windows = clients.computeIfAbsent(clientId, k -> new Windows(now));
        return windows.acquire(now, perMinute, perHour);
    }

    /** How many clients are tracked (for tests). */
    int trackedClients() {
        return clients.size();
    }

    private synchronized void makeRoom(long now) {
        clients.values().removeIf(w -> w.expired(now));
        if (clients.size() >= maxKeys) {
            clients.clear();
        }
    }

    /** One client's two fixed windows; guarded by its own monitor. */
    private static final class Windows {
        private long minuteStart;
        private int minuteCount;
        private long hourStart;
        private int hourCount;

        Windows(long now) {
            this.minuteStart = now;
            this.hourStart = now;
        }

        synchronized boolean acquire(long now, int perMinute, int perHour) {
            if (now - minuteStart >= MINUTE) {
                minuteStart = now;
                minuteCount = 0;
            }
            if (now - hourStart >= HOUR) {
                hourStart = now;
                hourCount = 0;
            }
            minuteCount = saturatedIncrement(minuteCount);
            hourCount = saturatedIncrement(hourCount);
            boolean minuteOk = perMinute <= 0 || minuteCount <= perMinute;
            boolean hourOk = perHour <= 0 || hourCount <= perHour;
            return minuteOk && hourOk;
        }

        synchronized boolean expired(long now) {
            return now - hourStart >= HOUR && now - minuteStart >= MINUTE;
        }

        private static int saturatedIncrement(int count) {
            return count == Integer.MAX_VALUE ? count : count + 1;
        }
    }
}
