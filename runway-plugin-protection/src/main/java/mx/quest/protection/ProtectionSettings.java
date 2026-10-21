package mx.quest.protection;

/**
 * A scope's login-protection policy — the settings the plugin's {@link LoginFormPolicyGuard} and
 * {@link SlidingWindowLoginGate} read to govern one realm/site's login. Persisted and resolved per scope by
 * {@link ProtectionConfigStore}: app-wide for the operator login, per-site for a tenant login.
 *
 * <p>Two always-on layers plus an opt-in third:
 * <ol>
 *   <li><b>Honeypot</b> ({@code honeypotEnabled}) — a hidden trap field a human never fills; a non-blank value
 *       marks the request as a bot.</li>
 *   <li><b>Per-IP lockout</b> ({@code maxAttempts} within {@code windowSeconds} → locked {@code lockoutSeconds}).</li>
 *   <li><b>External CAPTCHA</b> ({@link Captcha}) — a per-scope challenge verified server-side by
 *       {@link LoginFormPolicyGuard} via {@link CaptchaVerifier} when {@link Captcha#active() active}.</li>
 * </ol>
 *
 * <p>Immutable and self-describing; the captcha {@code secretKey} is stored encrypted ({@code ENC(...)}) by the
 * store and decrypted into the resolved settings so only the verifier — never a template or the client — sees it.
 * {@link #defaults()} is the fail-safe baseline used whenever no config file exists or one cannot be read.
 *
 * @param honeypotEnabled whether the hidden trap field is enforced
 * @param maxAttempts     failed logins within the window that trip the lockout
 * @param windowSeconds   the sliding window over which failures accumulate
 * @param lockoutSeconds  how long an IP stays locked once the threshold is reached
 * @param captcha         the (opt-in) CAPTCHA policy; never {@code null} (use {@link Captcha#disabled()})
 */
public record ProtectionSettings(
        boolean honeypotEnabled,
        int maxAttempts,
        int windowSeconds,
        int lockoutSeconds,
        Captcha captcha) {

    /**
     * The one lockout default, used by a fresh install ({@link ProtectionProperties.Login}), by a saved file that
     * omits a key ({@link StoredProtection}), by a non-positive value, and by {@link #defaults()}. Until 1.0.0 a
     * fresh install used 5 failures in 300 s while the other three used 10 in 900 s.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    /** See {@link #DEFAULT_MAX_ATTEMPTS}. */
    public static final int DEFAULT_WINDOW_SECONDS = 300;
    /** See {@link #DEFAULT_MAX_ATTEMPTS}. */
    public static final int DEFAULT_LOCKOUT_SECONDS = 900;

    /** Normalizes: a non-null captcha and positive thresholds (a hand-edited non-positive value falls back). */
    public ProtectionSettings {
        if (captcha == null) {
            captcha = Captcha.disabled();
        }
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
        if (windowSeconds <= 0) {
            windowSeconds = DEFAULT_WINDOW_SECONDS;
        }
        if (lockoutSeconds <= 0) {
            lockoutSeconds = DEFAULT_LOCKOUT_SECONDS;
        }
    }

    /**
     * The opt-in external CAPTCHA policy. The {@code secretKey} is sensitive and stored encrypted; it is read
     * server-side only, by {@link CaptchaVerifier} at challenge-verification time — never rendered into a view.
     *
     * @param enabled        whether a CAPTCHA challenge is required on the login form
     * @param provider       the reCAPTCHA mode ({@code recaptcha-v2} or {@code recaptcha-v3}); defaults to v2
     * @param siteKey        the public site key rendered into the challenge widget
     * @param secretKey      the private key used server-side to verify a response (stored {@code ENC(...)})
     * @param scoreThreshold the minimum acceptable score for the score-based v3 mode (0.0–1.0)
     */
    public record Captcha(boolean enabled, String provider, String siteKey, String secretKey, double scoreThreshold) {

        /** A disabled CAPTCHA with the default reCAPTCHA mode (v2) and score threshold. */
        public static Captcha disabled() {
            return new Captcha(false, "recaptcha-v2", "", "", 0.5);
        }

        /** Whether the challenge is enabled AND both keys are present (only then is a challenge actually issued). */
        public boolean active() {
            return enabled && siteKey != null && !siteKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }
    }

    /** The fail-safe baseline: honeypot on, captcha off, 5 failures in 5 min → 15 min lockout. */
    public static ProtectionSettings defaults() {
        return new ProtectionSettings(true, DEFAULT_MAX_ATTEMPTS, DEFAULT_WINDOW_SECONDS, DEFAULT_LOCKOUT_SECONDS,
                Captcha.disabled());
    }
}
