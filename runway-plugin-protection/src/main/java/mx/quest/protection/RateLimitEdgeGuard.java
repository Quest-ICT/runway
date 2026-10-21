package mx.quest.protection;

import java.util.Set;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.protect.EdgeRequest;
import mx.quest.runway.protect.EdgeVerdict;
import mx.quest.runway.protect.RejectionOutcome;
import mx.quest.runway.web.CidrSet;

/**
 * The DoS/abuse policy filling the kernel's {@link EdgeGuard} seam, evaluated on every request before the security
 * chain:
 * <ol>
 *   <li>a static <b>allowlist</b> (the configured trusted CIDRs — e.g. a monitoring probe) passes immediately.
 *       Loopback is <b>not</b> on it unless the operator lists it: it is never blockable (the blocklist refuses
 *       it), but it is rate-limited like any client — behind a same-host proxy every request can arrive from
 *       loopback, and an implicit exemption would turn the limit off for everyone;</li>
 *   <li>the mutable {@link IpBlocklist} (manual operator bans, persisted; auto-escalated bans, ephemeral) rejects
 *       — {@code 429} with the remaining time for an auto-escalation, {@code 403} for an operator ban —
 *       checked <b>before</b> the token bucket, so a banned IP never even consumes a token;</li>
 *   <li>otherwise a per-IP {@link TokenBucketRateLimiter} admits the request or throttles it {@code 429} with a
 *       short {@code Retry-After} — a throttle feeds the limiter's auto-escalation, which may add an auto block.</li>
 * </ol>
 *
 * <p>O(1) per request (a small CIDR-set check, a map lookup in the blocklist, and a token-bucket update).
 * Fail-<b>safe</b>: the blocklist check fails open on error (it never turns away legitimate traffic — the rate
 * limit still applies), and the static allowlist / loopback are never blockable in the first place (loopback's
 * throttles therefore never escalate into an auto-block).
 */
public final class RateLimitEdgeGuard implements EdgeGuard {

    private final CidrSet allowlist;
    private final IpBlocklist blocklist;
    private final TokenBucketRateLimiter limiter;

    public RateLimitEdgeGuard(CidrSet allowlist, IpBlocklist blocklist, TokenBucketRateLimiter limiter) {
        this.allowlist = allowlist;
        this.blocklist = blocklist;
        this.limiter = limiter;
    }

    @Override
    public EdgeVerdict check(EdgeRequest request) {
        String ip = request.clientIp();
        if (allowlist.contains(ip)) {
            return EdgeVerdict.pass();
        }

        // ONE lookup, and it reports WHICH kind of block matched — an operator ban and an auto-escalation are
        // indistinguishable on the wire but call for opposite responses.
        //
        // An auto-block IS "too many requests": the rate limiter escalating, not a decision about who may
        // read the site. Answering 403 with no Retry-After told a crawler it was forbidden, plausibly
        // permanently, for what is in fact a fifteen-minute time-out — and a crawler that concludes it is
        // banned stops coming back, which is how pages quietly leave an index. 429 + the real remaining time
        // says exactly what is true and exactly when to return.
        //
        // Manual and CIDR blocks keep 403: an operator ban genuinely is "forbidden", and there is no honest
        // retry time to offer. An auto-block always has an end (the limiter clamps block-seconds to at least 1);
        // should one ever arrive without an expiry it falls back to 403 for the same reason — better to say
        // nothing than to invent a number.
        IpBlocklist.Match block = blocklist.match(ip);
        if (block != null) {
            if (block.autoEscalated() && block.remainingSeconds() > 0) {
                return EdgeVerdict.reject(429, (int) Math.min(Integer.MAX_VALUE, block.remainingSeconds()),
                        block.kind());
            }
            return EdgeVerdict.reject(403, block.kind());
        }
        if (!limiter.tryAcquire(ip)) {
            return EdgeVerdict.reject(429, 1, "rate-limit");
        }
        return EdgeVerdict.pass();
    }

    /**
     * The five rejections this guard can actually answer with, so all five counters exist at zero from
     * startup rather than appearing on first use (see {@code EdgeGuard#declaredOutcomes}).
     *
     * <p>Enumerated as pairs, not as a status set crossed with a reason set: {@code blocklist-auto} appears
     * under <b>both</b> statuses because an auto-block normally carries a remaining time and answers
     * {@code 429}; the {@code 403} pair is the defensive fallback for one without an expiry, which the limiter
     * never creates today (it clamps block-seconds to at least 1) but a blocklist entry could. Whereas
     * a {@code 429} tagged {@code blocklist-manual} is unreachable by construction, and registering it would
     * publish a permanent zero asserting something no code path can produce.
     */
    @Override
    public Set<RejectionOutcome> declaredOutcomes() {
        return Set.of(
                new RejectionOutcome(429, "rate-limit"),
                new RejectionOutcome(429, "blocklist-auto"),
                new RejectionOutcome(403, "blocklist-auto"),
                new RejectionOutcome(403, "blocklist-manual"),
                new RejectionOutcome(403, "blocklist-cidr"));
    }
}
