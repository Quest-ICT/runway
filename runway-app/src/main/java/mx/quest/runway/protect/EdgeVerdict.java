package mx.quest.runway.protect;

/**
 * An {@link EdgeGuard}'s O(1) verdict: either pass the request to the chain, or reject it with an HTTP status
 * (and an optional {@code Retry-After}). A reject is emitted by {@link EdgeRejectFilter} as a tiny
 * {@code Connection: close} response that never reaches the security chain or a controller.
 *
 * <p><b>{@link #reason()} is what makes a rejection diagnosable.</b> A status alone cannot answer the only
 * question anyone asks when a request is blocked — <i>why</i>. A {@code 403} may be a manually blocklisted
 * address or an auto-escalation from repeated throttling; those call for opposite responses from an operator,
 * and the status is identical. The reason is a short, stable token (never a sentence, never user input) because
 * it is also a metric tag, so its cardinality must stay bounded.
 *
 * @param allowed           whether the request may proceed
 * @param status            the HTTP status to emit when not allowed (e.g. 403, 429)
 * @param retryAfterSeconds {@code Retry-After} seconds when {@code > 0} (typically with 429)
 * @param reason            a short stable token naming <em>why</em> ({@code blocklist}, {@code rate-limit}, …),
 *                          or {@code null} when unstated — reported as {@link #UNSPECIFIED}
 */
public record EdgeVerdict(boolean allowed, int status, int retryAfterSeconds, String reason) {

    /** The reason reported when a guard rejects without stating one. */
    public static final String UNSPECIFIED = "unspecified";

    private static final EdgeVerdict PASS = new EdgeVerdict(true, 0, 0, null);

    public EdgeVerdict {
        // Normalised at construction so the log line and the metric tag can never disagree, and so a guard
        // returning "" does not create a distinct empty-string tag value alongside "unspecified".
        reason = (reason == null || reason.isBlank()) ? null : reason.trim();
    }

    /**
     * The pre-{@code reason} shape, kept so an {@link EdgeGuard} compiled against an earlier kernel keeps
     * loading (the protection plugin is an external artifact). Rejections built this way report
     * {@link #UNSPECIFIED}.
     */
    public EdgeVerdict(boolean allowed, int status, int retryAfterSeconds) {
        this(allowed, status, retryAfterSeconds, null);
    }

    /** The reason token for logs and metrics — never {@code null}. */
    public String reasonOrUnspecified() {
        return reason == null ? UNSPECIFIED : reason;
    }

    /** Allow the request through. */
    public static EdgeVerdict pass() {
        return PASS;
    }

    /** Reject with {@code status} (no {@code Retry-After}), reason unstated. */
    public static EdgeVerdict reject(int status) {
        return new EdgeVerdict(false, status, 0, null);
    }

    /** Reject with {@code status} and a {@code Retry-After} hint, reason unstated. */
    public static EdgeVerdict reject(int status, int retryAfterSeconds) {
        return new EdgeVerdict(false, status, retryAfterSeconds, null);
    }

    /** Reject with {@code status} and a stated {@code reason} — the form a real guard should use. */
    public static EdgeVerdict reject(int status, String reason) {
        return new EdgeVerdict(false, status, 0, reason);
    }

    /** Reject with {@code status}, a {@code Retry-After} hint, and a stated {@code reason}. */
    public static EdgeVerdict reject(int status, int retryAfterSeconds, String reason) {
        return new EdgeVerdict(false, status, retryAfterSeconds, reason);
    }
}
