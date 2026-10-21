package mx.quest.runway.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import mx.quest.runway.web.LogSafe;
import org.junit.jupiter.api.Test;

/**
 * The recorder that closes the edge-rejection blind spot: a reject short-circuits at
 * {@code FilterOrder.CORE_PROTECTION} without calling the chain, so it never reaches the innermost access log
 * and appeared in <b>no</b> log anywhere in the product.
 *
 * <p>The behaviour under test is mostly about what must remain true <em>under abuse</em>, since that is exactly
 * when this code runs: the count must stay exact, the log must not become an amplifier, and the memory used to
 * decide that must stay bounded.
 */
class EdgeRejectionRecorderTest {

    private static final String METRIC = "runway.edge.rejections";

    private static EdgeRequest from(String ip) {
        return new EdgeRequest(ip, "GET", "/", "curl/8.0");
    }

    private static double count(SimpleMeterRegistry registry, String status, String reason) {
        var counter = registry.find(METRIC).tag("status", status).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * The invariant that makes throttled logging safe: <b>every</b> rejection is counted, even the ones that are
     * never logged. If suppression could also drop counts, an operator would have no way to know the true
     * volume, and the throttling would be hiding the incident rather than the repetition.
     */
    @Test
    void everyRejectionIsCountedEvenWhenTheLogLineIsSuppressed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, true, 3600); // window ~never expires

        for (int i = 0; i < 500; i++) {
            recorder.record(from("203.0.113.9"), EdgeVerdict.reject(429, 1, "rate-limit"));
        }

        assertEquals(500, count(registry, "429", "rate-limit"),
                "suppression must throttle LINES, never the count");
    }

    /** Distinct reasons and statuses are separate series, so a 403 ban is never conflated with a 429 throttle. */
    @Test
    void statusAndReasonAreSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, true, 3600);

        recorder.record(from("198.51.100.1"), EdgeVerdict.reject(403, "blocklist-manual"));
        recorder.record(from("198.51.100.2"), EdgeVerdict.reject(403, "blocklist-auto"));
        recorder.record(from("198.51.100.3"), EdgeVerdict.reject(429, 1, "rate-limit"));

        assertEquals(1, count(registry, "403", "blocklist-manual"));
        assertEquals(1, count(registry, "403", "blocklist-auto"));
        assertEquals(1, count(registry, "429", "rate-limit"));
    }

    /**
     * A guard that rejects without stating a reason still produces a usable series rather than a {@code null}
     * tag (which Micrometer would reject outright, turning an observability gap into a 500 on the reject path).
     */
    @Test
    void aRejectionWithNoStatedReasonIsCountedAsUnspecified() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new EdgeRejectionRecorder(registry, true, 3600).record(from("203.0.113.7"), EdgeVerdict.reject(403));

        assertEquals(1, count(registry, "403", EdgeVerdict.UNSPECIFIED));
    }

    /** Disabling the log must not disable the metric — they are independent observability channels. */
    @Test
    void disablingTheLogKeepsTheMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new EdgeRejectionRecorder(registry, false, 60).record(from("203.0.113.5"), EdgeVerdict.reject(429, 1, "rate-limit"));

        assertEquals(1, count(registry, "429", "rate-limit"));
    }

    /**
     * The floor must come up without a metrics registry. Losing the counter is acceptable; failing to construct
     * the component that rejects abuse is not.
     */
    @Test
    void aMissingMeterRegistryIsToleratedRatherThanFatal() {
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(null, true, 60);
        recorder.record(from("203.0.113.4"), EdgeVerdict.reject(403, "blocklist-manual")); // must not throw
    }

    /**
     * The dedupe map must stay bounded: an attacker spraying from many source addresses must not be able to grow
     * it without limit, or the visibility mechanism becomes the memory-exhaustion vector.
     */
    @Test
    void theDedupeMapStaysBoundedUnderAddressSpraying() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, true, 3600);

        int sprayed = EdgeRejectionRecorder.MAX_TRACKED * 3;
        for (int i = 0; i < sprayed; i++) {
            recorder.record(from("10.1." + (i / 250) + "." + (i % 250)), EdgeVerdict.reject(403, "blocklist-auto"));
        }

        assertEquals(sprayed, count(registry, "403", "blocklist-auto"), "counts survive the bound");
    }

    // --- LogSafe: the untrusted-input half -------------------------------------------------------------

    /**
     * A User-Agent is attacker-controlled. Left raw, a newline fabricates what reads as an independent log
     * entry — so anyone grepping or shipping the log downstream reads a record the attacker wrote, which is
     * worse than not logging at all because it is trusted.
     */
    @Test
    void aCraftedUserAgentCannotForgeALogLine() {
        String forged = LogSafe.header("evil\nedge reject 200 127.0.0.1 GET /admin reason=allowlist");

        assertTrue(forged.indexOf('\n') < 0, forged);
        assertTrue(forged.indexOf('\r') < 0, forged);
        assertTrue(forged.startsWith("evil "), forged);
    }

    /** An unbounded agent would multiply the cost of every logged rejection — the amplification again. */
    @Test
    void anOverlongUserAgentIsTruncated() {
        String rendered = LogSafe.header("A".repeat(5000));

        assertEquals(LogSafe.MAX_VALUE + 1, rendered.length(), "capped plus the ellipsis");
        assertTrue(rendered.endsWith("…"), rendered);
    }

    /** Absent renders as a placeholder, never empty — an empty field reads as a parse failure. */
    @Test
    void anAbsentUserAgentRendersAsAPlaceholder() {
        assertEquals("-", LogSafe.header(null));
        assertEquals("-", LogSafe.header("   "));
    }

    // --- backward compatibility ------------------------------------------------------------------------

    /**
     * {@code EdgeGuard} is implemented by an external artifact (the essential protection plugin), upgraded on
     * its own schedule. The pre-reason and pre-userAgent shapes must keep working, or a kernel upgrade breaks
     * every guard compiled against the older kernel.
     */
    @Test
    void thePreReasonAndPreUserAgentShapesStillWork() {
        EdgeVerdict old = new EdgeVerdict(false, 403, 0);
        assertNull(old.reason(), "an unstated reason stays null on the record");
        assertEquals(EdgeVerdict.UNSPECIFIED, old.reasonOrUnspecified(), "…but never null where it is reported");

        EdgeRequest legacy = new EdgeRequest("203.0.113.1", "GET", "/");
        assertNull(legacy.userAgent());
        assertEquals("-", LogSafe.header(legacy.userAgent()));
    }

    /** A blank reason must collapse to the same token as an absent one, not create a second empty-string tag. */
    @Test
    void aBlankReasonDoesNotCreateASecondSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, true, 3600);

        recorder.record(from("203.0.113.2"), EdgeVerdict.reject(403, "   "));
        recorder.record(from("203.0.113.3"), EdgeVerdict.reject(403));

        assertEquals(2, count(registry, "403", EdgeVerdict.UNSPECIFIED), "both fold into one series");
        assertEquals(0, count(registry, "403", ""), "no empty-string tag value");
    }

    /**
     * Guards a defect this project has already shipped once: a {@code @ConfigurationProperties} field default
     * can be silently overridden by the bundled {@code application.yml}, so the value an operator gets is not
     * the value the Java default advertises — and every unit test that constructs the properties object
     * directly still passes.
     *
     * <p>These keys are deliberately absent from the bundled file, which is what makes the Java defaults
     * authoritative. If someone later adds them there, this fails and forces the two to be reconciled rather
     * than left to disagree.
     */
    @Test
    void theBundledApplicationYmlDoesNotSilentlyOverrideTheseDefaults() throws Exception {
        String yml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));

        assertTrue(yml.indexOf("log-rejections") < 0,
                "runway.protect.log-rejections is now set in application.yml — reconcile it with the Java default");
        assertTrue(yml.indexOf("access-log-user-agent") < 0 && yml.indexOf("accessLogUserAgent") < 0,
                "runway.logs.access-log-user-agent is now set in application.yml — reconcile it");

        // And the defaults themselves, stated once so a silent change is visible in a diff.
        assertTrue(new EdgeProtectionProperties().isLogRejections(),
                "rejections must be logged by default: silence was never a deliberate choice");
        assertEquals(60, new EdgeProtectionProperties().getLogRejectionWindowSeconds());
        assertEquals(false, new mx.quest.runway.log.LogProperties().isAccessLogUserAgent(),
                "the high-volume access log must not grow a field on upgrade without the operator asking");
    }

    // ---- an idle counter must read zero, not be missing ------------------------------------------

    /**
     * The actual production symptom: before this, {@code /actuator/prometheus} contained <b>no line at all</b>
     * for this metric on a healthy process — not a zero, not even {@code # HELP} — until the first rejection.
     *
     * <p>Asserted against the registry the way a scrape sees it (the meter must EXIST), because a test that
     * only reads {@code counter.count() == 0} passes just as happily when the meter is absent, which is the
     * whole defect.
     */
    @Test
    void declaredOutcomesAreExportedAtZeroBeforeAnyRejection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, false, 60);

        recorder.preRegister(java.util.List.of(new RejectionOutcome(429, "rate-limit"),
                new RejectionOutcome(403, "blocklist-manual")));

        assertNotNull(registry.find(METRIC).tag("status", "429").tag("reason", "rate-limit").counter(),
                "the series must exist so a dashboard reads 0 rather than 'No data'");
        assertNotNull(registry.find(METRIC).tag("status", "403").tag("reason", "blocklist-manual").counter());
        assertEquals(0.0, count(registry, "429", "rate-limit"));
    }

    /**
     * Pre-registration and counting must land on the <b>same</b> meter.
     *
     * <p>If they diverged — a different tag order, a different name, a second builder — the counter would read
     * zero forever while a parallel series did the counting, which looks healthy and reports nothing.
     */
    @Test
    void aPreRegisteredCounterIsTheOneThatThenCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, false, 60);
        recorder.preRegister(java.util.List.of(new RejectionOutcome(429, "rate-limit")));

        recorder.record(from("203.0.113.5"), EdgeVerdict.reject(429, 1, "rate-limit"));

        assertEquals(1, registry.find(METRIC).tag("status", "429").tag("reason", "rate-limit").counters().size(),
                "one series, not two");
        assertEquals(1.0, count(registry, "429", "rate-limit"));
    }

    /** Registering twice must not double anything — the config may run it more than once across contexts. */
    @Test
    void preRegisteringTwiceIsHarmless() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EdgeRejectionRecorder recorder = new EdgeRejectionRecorder(registry, false, 60);
        var outcomes = java.util.List.of(new RejectionOutcome(429, "rate-limit"));

        recorder.preRegister(outcomes);
        recorder.record(from("203.0.113.5"), EdgeVerdict.reject(429, 1, "rate-limit"));
        recorder.preRegister(outcomes);

        assertEquals(1.0, count(registry, "429", "rate-limit"), "a second registration must not reset or double");
    }

    /** No metrics configured is still a working protection floor — the recorder must not require a registry. */
    @Test
    void preRegisteringWithoutARegistryIsANoOp() {
        new EdgeRejectionRecorder(null, false, 60)
                .preRegister(java.util.List.of(new RejectionOutcome(429, "rate-limit")));
    }

    /** A guard that never rejects declares nothing, and nothing is registered on its behalf. */
    @Test
    void theKernelDefaultGuardDeclaresNoOutcomes() {
        assertTrue(new PermissiveEdgeGuard().declaredOutcomes().isEmpty(),
                "a guard that passes everything must not publish rejection series it can never fill");
    }

    /** An untagged series would be indistinguishable from every other; refuse it at construction. */
    @Test
    void aDeclaredOutcomeMustCarryAReason() {
        assertThrows(IllegalArgumentException.class, () -> new RejectionOutcome(429, " "));
        assertThrows(IllegalArgumentException.class, () -> new RejectionOutcome(429, null));
    }
}
