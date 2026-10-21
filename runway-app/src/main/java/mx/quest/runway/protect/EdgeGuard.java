package mx.quest.runway.protect;

/**
 * The kernel's EDGE_INGRESS reject <b>seam</b> — the single O(1) decision consulted by {@link EdgeRejectFilter}
 * at the protection floor ({@code FilterOrder.CORE_PROTECTION}), ahead of the security chain. This is the
 * "floor in kernel, policy in plugin" boundary: the kernel owns the slot and a fail-safe
 * default; the <b>rich policy</b> — per-IP token-bucket rate limiting, manual/auto IP blocklists, escalation,
 * allowlists — is the essential protection plugin's implementation.
 *
 * <p>The kernel default ({@code PermissiveEdgeGuard}) <b>passes everything</b>: without the essential plugin the
 * platform boots with no abuse defense (degrade-don't-crash). The fail-<i>closed</i> behavior (a
 * {@code 503} for tenant hosts when an allowlisted-essential plugin failed to load) is a <b>separate</b>
 * degraded-mode mechanism, not a stricter bean replacing this one — so a failed essential plugin can never
 * silently fall back to permissive. An implementation must be O(1) and side-effect-light (it runs on every
 * request before the chain).
 */
@FunctionalInterface
public interface EdgeGuard {

    /** The verdict for an incoming request, evaluated before it enters the security chain. */
    EdgeVerdict check(EdgeRequest request);

    /**
     * The rejection outcomes this guard may produce, so their counters can exist at <b>zero</b> from startup
     * rather than appearing on first use.
     *
     * <h2>Why "zero" and "absent" are not the same thing</h2>
     * A Micrometer counter with no children is not exported at all — no {@code # HELP}, no {@code # TYPE}, no
     * sample. So a healthy platform that has rejected nothing since its last restart publishes <i>nothing</i>
     * about its protection edge, and a dashboard reads "No data" where the truth is "the guard is running and
     * turning nobody away". The two are indistinguishable at 03:00.
     *
     * <p>It has already cost a false alarm: a disappearance detector — built after a real metric rename —
     * fired on this counter going missing when it was merely idle after a restart. The tempting fix is to
     * suppress that detector around restarts, which is exactly wrong: <b>a rename ships inside a release, and
     * a release arrives at the moment of a restart</b>, so the suppression window is precisely the window a
     * real rename lands in. The fix belongs here.
     *
     * <p>Declared, never inferred. The set must stay small and closed — it becomes permanently-exported time
     * series — so return the handful of {@code (status, reason)} pairs the guard can actually answer with, and
     * nothing speculative.
     *
     * @return the declared outcomes; empty by default, so a guard that never rejects (and every guard written
     *         before this method existed) is unaffected
     */
    default java.util.Set<RejectionOutcome> declaredOutcomes() {
        return java.util.Set.of();
    }
}
