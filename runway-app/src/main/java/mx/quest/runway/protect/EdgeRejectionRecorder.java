package mx.quest.runway.protect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import mx.quest.runway.web.LogSafe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes edge rejections <b>visible</b> — the one thing the protection floor could not do.
 *
 * <h2>The blind spot this closes</h2>
 * {@link EdgeRejectFilter} sits at {@code FilterOrder.CORE_PROTECTION}, the outermost slot after the forwarded
 * header gate, and a reject <b>does not call the chain</b>. {@code AccessLogFilter} sits at
 * {@code CORE_ACCESS_LOG}, inside the floor (on purpose: see the throttling below). So every edge
 * {@code 403}/{@code 429} short-circuits <i>before</i> anything logs it: without this recorder the request
 * appears in no log anywhere in the product, and the access log carries a {@code User-Agent} only when asked to. The practical result was that nobody could answer either half of the
 * obvious question — <i>was a crawler throttled</i>, and <i>why was this request rejected</i>.
 *
 * <p>A rejection is also emitted <b>before</b> {@code CORE_SITE_CONTEXT}, so there is no resolved site and it
 * cannot be attributed to a tenant. It goes to the platform log only. That is a consequence of the ordering,
 * not an oversight: the ordering is what makes the floor absolute, and moving the guard inward to gain a site
 * id would mean abuse traffic reaching the security chain first.
 *
 * <h2>Why the logging is throttled, and why that is not a nicety</h2>
 * Logging every rejection would turn the guard into an <b>amplifier of the attack it is stopping</b>: a flood
 * the rate limiter correctly rejects in O(1) would become a flood of disk writes, and the cheap rejection path
 * (a tiny {@code Connection: close} response) would acquire an unbounded cost. Filling the disk is a worse
 * outcome than the traffic.
 *
 * <p>So a given {@code (clientIp, reason)} pair is logged at most once per {@link #windowMillis} window; the
 * suppressed remainder is counted and reported on the next line for that pair, so the volume is never hidden —
 * only the repetition is. <b>The metric is always incremented</b>, on every rejection without exception, which
 * is what makes throttled logging safe: the count stays exact even when the lines do not.
 *
 * <p>The dedupe map is bounded and cleared wholesale when it exceeds {@link #MAX_TRACKED} — an attacker
 * spraying from many addresses must not be able to grow it without limit. Clearing costs at worst one extra log
 * line per pair afterwards, which is the right thing to trade away under exactly that pressure.
 *
 * <h2>Cardinality</h2>
 * The counter is tagged by {@code status} and {@code reason} only — both small, closed sets. It is deliberately
 * <b>never</b> tagged by IP or path: those are attacker-controlled and unbounded, and a time-series database is
 * far easier to destroy with tag cardinality than with request volume.
 */
public class EdgeRejectionRecorder {

    /** Its own logger name so an operator can route or silence rejections independently of the access log. */
    private static final Logger log = LoggerFactory.getLogger("runway.protect");

    private static final String METRIC = "runway.edge.rejections";

    /** Upper bound on tracked {@code (ip, reason)} pairs before the dedupe map is dropped wholesale. */
    static final int MAX_TRACKED = 4096;

    private final MeterRegistry registry;
    private final boolean logRejections;
    private final long windowMillis;

    /** {@code ip|reason} → state. Bounded by {@link #MAX_TRACKED}. */
    private final Map<String, Suppression> seen = new ConcurrentHashMap<>();

    public EdgeRejectionRecorder(MeterRegistry registry, boolean logRejections, int windowSeconds) {
        this.registry = registry;
        this.logRejections = logRejections;
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
    }

    /**
     * Records one rejection: always counts it, logs it if this {@code (ip, reason)} has not been logged inside
     * the current window.
     *
     * @param request the rejected request (its {@code userAgent} is untrusted client input — see
     *                {@link EdgeRequest})
     * @param verdict the guard's rejection
     */
    public void record(EdgeRequest request, EdgeVerdict verdict) {
        String reason = verdict.reasonOrUnspecified();
        count(verdict.status(), reason);
        if (!logRejections) {
            return;
        }
        String key = request.clientIp() + "|" + reason;
        long now = System.currentTimeMillis();

        if (seen.size() > MAX_TRACKED) {
            seen.clear();
        }
        Suppression state = seen.computeIfAbsent(key, k -> new Suppression());
        long suppressed = state.claim(now, windowMillis);
        if (suppressed < 0) {
            return; // inside the window — counted, not logged
        }
        if (suppressed == 0) {
            log.warn("edge reject {} {} {} {} reason={} ua={}", verdict.status(), request.clientIp(),
                    request.method(), request.path(), reason, LogSafe.header(request.userAgent()));
        } else {
            log.warn("edge reject {} {} {} {} reason={} ua={} (+{} suppressed in the last {}s)",
                    verdict.status(), request.clientIp(), request.method(), request.path(), reason,
                    LogSafe.header(request.userAgent()), suppressed, windowMillis / 1000L);
        }
    }

    /**
     * Creates a zero-valued counter for each outcome the active guard declares, so the series exist from
     * startup instead of materialising on the first rejection.
     *
     * <p>Idempotent: {@code Counter.builder(...).register(...)} returns the existing meter for a
     * name/tag combination, so registering here and incrementing later act on the same counter — and calling
     * this twice cannot double-count. Silently does nothing when metrics are absent, on the same reasoning as
     * the rest of this class: the protection floor must come up in every configuration.
     *
     * @param outcomes the guard's declared {@code (status, reason)} pairs; {@code null} or empty is a no-op
     */
    public void preRegister(java.util.Collection<RejectionOutcome> outcomes) {
        if (registry == null || outcomes == null) {
            return;
        }
        for (RejectionOutcome outcome : outcomes) {
            counter(outcome.status(), outcome.reason());
        }
    }

    private void count(int status, String reason) {
        Counter counter = counter(status, reason);
        if (counter != null) {
            counter.increment();
        }
    }

    private Counter counter(int status, String reason) {
        if (registry == null) {
            return null;
        }
        return Counter.builder(METRIC)
                .description("Requests rejected at the protection edge, by status and reason")
                .tag("status", Integer.toString(status))
                .tag("reason", reason)
                .register(registry);
    }

    /** Per-{@code (ip, reason)} window state: when it was last logged, and how many were swallowed since. */
    private static final class Suppression {

        private final AtomicLong lastLoggedAt = new AtomicLong(0);
        private final AtomicLong suppressed = new AtomicLong(0);

        /**
         * Claims the right to log, or declines it.
         *
         * @return {@code -1} to stay silent (inside the window); otherwise the number of rejections suppressed
         *         since the previous line for this pair ({@code 0} on the first)
         */
        long claim(long now, long windowMillis) {
            long last = lastLoggedAt.get();
            if (now - last < windowMillis && last != 0) {
                suppressed.incrementAndGet();
                return -1;
            }
            // A racing caller may also win here; the cost is a duplicate line, never a lost count.
            if (!lastLoggedAt.compareAndSet(last, now)) {
                suppressed.incrementAndGet();
                return -1;
            }
            return suppressed.getAndSet(0);
        }
    }
}
