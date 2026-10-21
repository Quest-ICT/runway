package mx.quest.runway.security;

/**
 * The outcome of a {@link LoginFormGuard} check: either {@link #ok()} (let authentication proceed) or a rejection
 * carrying a machine-readable {@link #reason()} code. The reason is a small, open string vocabulary rather than a
 * fixed enum on purpose — it keeps the seam forward-compatible: the honeypot rejects with {@link Reason#HONEYPOT},
 * CAPTCHA adds {@link Reason#CAPTCHA} (and any future deterrent adds its own) without changing this type or the
 * {@link LoginProtectionFilter} that consumes it.
 *
 * <p>The filter maps every non-allowed verdict to a redirect back to the login page; the honeypot rejection uses
 * the generic {@code ?error} flag (indistinguishable from a bad password, so a bot cannot tell the trap tripped),
 * while a distinguishable reason (e.g. {@code captcha}) may drive its own flag — see {@link Reason#redirectFlag}.
 *
 * @param allowed whether the login may proceed to authentication
 * @param reason  the rejection reason when {@code !allowed} (never {@code null} then), or {@code null} when allowed
 */
public record LoginFormVerdict(boolean allowed, String reason) {

    /** Well-known rejection reason codes. New deterrents add a constant here; the seam type never changes. */
    public static final class Reason {
        /** The hidden trap field was filled — treated as a bot; redirected with the generic {@code ?error} flag. */
        public static final String HONEYPOT = "honeypot";
        /** A CAPTCHA challenge failed verification; redirected with the {@code ?captcha} flag. */
        public static final String CAPTCHA = "captcha";

        private Reason() {
        }

        /**
         * The login-page query flag for a rejection reason. The honeypot deliberately maps to {@code error} (a bot
         * must not learn the trap exists); any other reason maps to a flag named after itself (e.g. {@code captcha}),
         * so a legitimate user who fails it can be shown a specific message.
         */
        public static String redirectFlag(String reason) {
            return HONEYPOT.equals(reason) ? "error" : reason;
        }
    }

    /** A passing verdict — authentication proceeds. */
    public static LoginFormVerdict ok() {
        return new LoginFormVerdict(true, null);
    }

    /** A rejecting verdict carrying {@code reason} (one of the {@link Reason} codes). */
    public static LoginFormVerdict reject(String reason) {
        return new LoginFormVerdict(false, reason);
    }

    /** The login-page query flag this verdict should redirect with (only meaningful when {@code !allowed}). */
    public String redirectFlag() {
        return Reason.redirectFlag(reason);
    }
}
