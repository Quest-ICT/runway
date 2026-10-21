package mx.quest.runway.web;

/**
 * Renders <b>untrusted client input</b> safe to place in a log line.
 *
 * <p>Anything taken from a request header, a query string or a body is attacker-controlled, and a log line is
 * not an inert destination. Two concrete failures follow from writing such a value raw:
 *
 * <ul>
 *   <li><b>Log forging.</b> A value containing {@code \n} produces what looks like an additional, independent
 *       log entry. Anyone grepping, shipping to a collector, or parsing line-by-line then reads a record the
 *       attacker wrote — which is worse than no logging, because it is trusted.</li>
 *   <li><b>Amplification.</b> An unbounded value multiplies the cost of every logged request. On a surface that
 *       already exists to absorb abuse cheaply, that hands back the cost the surface was built to avoid.</li>
 * </ul>
 *
 * <p>Lives in {@code web} because both the access log and the protection edge need it, and neither should
 * depend on the other: sanitising a header is a property of handling HTTP, not of either feature.
 */
public final class LogSafe {

    /** Cap for a logged header value. Long enough for real agent strings, short enough to bound a line. */
    public static final int MAX_VALUE = 160;

    private LogSafe() {
    }

    /**
     * A header value rendered for a log line: {@code -} when absent or blank, control characters folded to
     * spaces, and truncated to {@link #MAX_VALUE} with an ellipsis.
     *
     * <p>Returns a placeholder rather than an empty string so a missing value cannot be mistaken for a parse
     * failure or shift a positional field.
     */
    public static String header(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String clean = raw.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= MAX_VALUE ? clean : clean.substring(0, MAX_VALUE) + "…";
    }
}
