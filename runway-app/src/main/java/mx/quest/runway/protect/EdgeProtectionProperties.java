package mx.quest.runway.protect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Observability settings for the protection floor, bound from {@code runway.protect.*}.
 *
 * <p>Deliberately narrow: this covers only how rejections are <b>reported</b>. The reject <i>policy</i> —
 * allowlists, blocklists, rate limits, escalation — belongs to whichever {@link EdgeGuard} is installed (the
 * essential protection plugin supplies the real one) and is configured there. Keeping the two apart means an
 * operator can turn rejection logging up while diagnosing without touching a single protection threshold, which
 * is exactly the moment you least want to be editing thresholds.
 */
@ConfigurationProperties("runway.protect")
public class EdgeProtectionProperties {

    /**
     * Whether an edge rejection writes a log line (to the {@code runway.protect} logger). Default {@code true}:
     * before this existed, a {@code 403}/{@code 429} from the edge appeared in <b>no log at all</b>, because the
     * reject short-circuits outside the access log. Silence was never a deliberate choice, so it is not kept as
     * the default.
     *
     * <p>Set {@code false} only if you collect rejections another way (an upstream proxy log, or the
     * {@code runway_edge_rejections_total} metric). The metric is unaffected by this flag and keeps counting.
     */
    private boolean logRejections = true;

    /**
     * How long, in seconds, to suppress repeat log lines for the same {@code (client IP, reason)} pair. Default
     * {@code 60}.
     *
     * <p>This is a flood guard, not a preference. Logging every rejection would let a flood the limiter rejects
     * in O(1) become an unbounded stream of disk writes — the guard amplifying the attack it is stopping.
     * Suppressed rejections are counted and reported on the next line for that pair, so volume is never hidden;
     * only repetition is. Lower it while diagnosing; raise it under sustained abuse.
     */
    private int logRejectionWindowSeconds = 60;

    public boolean isLogRejections() {
        return logRejections;
    }

    public void setLogRejections(boolean logRejections) {
        this.logRejections = logRejections;
    }

    public int getLogRejectionWindowSeconds() {
        return logRejectionWindowSeconds;
    }

    public void setLogRejectionWindowSeconds(int logRejectionWindowSeconds) {
        this.logRejectionWindowSeconds = logRejectionWindowSeconds;
    }
}
