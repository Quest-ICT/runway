package mx.quest.protection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import mx.quest.runway.security.LoginAttemptGate;

/**
 * The brute-force lockout policy: per-IP, count failed logins within a sliding {@code windowMillis}; once
 * {@code maxAttempts} fall inside the window the IP is locked for {@code lockoutMillis}. A success clears the
 * IP. Implements the kernel's {@link LoginAttemptGate} seam, so {@code LoginProtectionFilter} blocks a locked
 * IP before authentication. Thread-safe; the clock is injectable for deterministic tests.
 *
 * <p><b>Config-driven, read fresh per call.</b> The three thresholds are pulled from a {@link Supplier} of the
 * current {@link ProtectionSettings} (backed by {@link ProtectionConfigStore}) on every failure and lock check,
 * <b>not</b> frozen at construction — so an operator (or the Protection screens) changing the app-level protection
 * config takes effect without a restart. The seam is IP-only (it has no per-request scope), so the gate uses the
 * <b>application-scope</b> settings for its thresholds; per-site login deterrents that need the request live in
 * the request-aware {@link LoginFormPolicyGuard}. A bad/absent config degrades to the settings' own defaults (fail-safe;
 * {@link ProtectionSettings} normalizes non-positive values), so the lockout never disappears or misbehaves.
 */
public final class SlidingWindowLoginGate implements LoginAttemptGate {

    private static final int DEFAULT_MAX_IPS = 100_000;

    private final Supplier<ProtectionSettings> settings;
    private final int maxIps;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, Attempts> byIp = new ConcurrentHashMap<>();

    public SlidingWindowLoginGate(Supplier<ProtectionSettings> settings, LongSupplier nowMillis) {
        this(settings, DEFAULT_MAX_IPS, nowMillis);
    }

    public SlidingWindowLoginGate(Supplier<ProtectionSettings> settings, int maxIps, LongSupplier nowMillis) {
        this.settings = settings;
        this.maxIps = Math.max(1, maxIps);
        this.nowMillis = nowMillis;
    }

    @Override
    public boolean isLocked(String clientIp) {
        Attempts attempts = byIp.get(clientIp);
        return attempts != null && attempts.lockedUntil() > nowMillis.getAsLong();
    }

    @Override
    public void recordFailure(String clientIp) {
        ProtectionSettings current = current();
        int maxAttempts = current.maxAttempts();
        long windowMillis = current.windowSeconds() * 1000L;
        long lockoutMillis = current.lockoutSeconds() * 1000L;

        long now = nowMillis.getAsLong();
        if (byIp.size() >= maxIps && !byIp.containsKey(clientIp)) {
            // bound memory against a many-IP login flood: reclaim entries with no in-window failures and no
            // active lockout; if still at the cap, clear (a crude floor — better than unbounded heap growth).
            byIp.entrySet().removeIf(entry -> entry.getValue().isDead(now, windowMillis));
            if (byIp.size() >= maxIps) {
                byIp.clear();
            }
        }
        byIp.computeIfAbsent(clientIp, k -> new Attempts()).onFailure(now, windowMillis, maxAttempts, lockoutMillis);
    }

    @Override
    public void recordSuccess(String clientIp) {
        byIp.remove(clientIp);
    }

    /** The current app-scope thresholds; a null/failing supplier degrades to the settings defaults (fail-safe). */
    private ProtectionSettings current() {
        if (settings == null) {
            return ProtectionSettings.defaults();
        }
        try {
            ProtectionSettings resolved = settings.get();
            return resolved != null ? resolved : ProtectionSettings.defaults();
        } catch (RuntimeException e) {
            return ProtectionSettings.defaults();
        }
    }

    private static final class Attempts {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long lockedUntil;

        synchronized void onFailure(long now, long windowMillis, int maxAttempts, long lockoutMillis) {
            while (!failures.isEmpty() && failures.peekFirst() < now - windowMillis) {
                failures.pollFirst(); // drop failures older than the window
            }
            failures.addLast(now);
            if (failures.size() >= maxAttempts) {
                lockedUntil = now + lockoutMillis;
            }
        }

        synchronized long lockedUntil() {
            return lockedUntil;
        }

        /** Whether this entry can be reclaimed: no failures left in the window and no active lockout. */
        synchronized boolean isDead(long now, long windowMillis) {
            while (!failures.isEmpty() && failures.peekFirst() < now - windowMillis) {
                failures.pollFirst();
            }
            return failures.isEmpty() && lockedUntil <= now;
        }
    }
}
