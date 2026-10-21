package mx.quest.protection;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.security.TrustedDeviceIssuer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The plugin's "remember this device" policy — the {@code @Primary} {@link TrustedDeviceIssuer} that mints a
 * signed trusted-device cookie on a successful login (when the user opted in), plus the matching
 * {@link #isTrusted(HttpServletRequest, ProtectionScope) trust check} the login form guard and the login-view
 * contributor consult to SKIP the CAPTCHA on a trusted device. It is the opt-in convenience that answers "CAPTCHA
 * on every login is annoying" without removing the deterrent for anyone who hasn't proven themselves.
 *
 * <h2>Why it doesn't weaken security</h2>
 * <ul>
 *   <li><b>Issued only after a successful login.</b> A cookie is minted at login-success time (see
 *       {@link TrustedDeviceSuccessHandler}) and only when the scope's CAPTCHA is active — which means the CAPTCHA
 *       was solved on that very login. A brute-forcing bot never authenticates, so it never earns a cookie.</li>
 *   <li><b>Skips only the CAPTCHA, never authentication.</b> Credentials are still required on every login, and
 *       the per-IP brute-force lockout ({@code SlidingWindowLoginGate}) still applies to every attempt.</li>
 *   <li><b>Tamper-proof + scoped.</b> The cookie is AES-256-GCM sealed via the kernel {@link SecretCipher} (the
 *       GCM auth tag is the integrity guarantee — a modified cookie fails to decrypt) and carries its realm + site
 *       + expiry, re-checked against the resolved scope on every read. A cookie for site A can never skip site B's
 *       CAPTCHA (host-only cookie + per-site name + payload binding — three independent scopings), and an app
 *       cookie can never skip a tenant CAPTCHA or vice-versa.</li>
 *   <li><b>Fail-safe.</b> A missing key, a forged/plaintext value, a tampered ciphertext, a wrong scope, or an
 *       expired stamp all resolve to "not trusted" ⇒ the CAPTCHA is shown. The feature can only ever cause "more
 *       CAPTCHA", never "less than intended".</li>
 * </ul>
 *
 * <p>Device-scoped (not user-scoped) by necessity: the login GET renders the CAPTCHA before any username is
 * entered, so the skip decision cannot depend on identity. This matches the common "remember this device" model
 * and is safe here because the cookie only skips a challenge, never a credential check. Stateless (the payload
 * lives in the cookie, not a server store), so it needs no cluster-replicated state; the trade-off is that an
 * outstanding cookie can only be invalidated by its own expiry or by rotating the master key.
 *
 * <p>Note (deliberate non-hardening): the cookie is sealed with {@link SecretCipher} under the same master key as
 * other stored secrets, with no crypto AAD/domain separation. This is not exploitable — a ciphertext from another
 * context (e.g. a stored SMTP password) would have to decrypt to exactly {@code REALM|siteId|<future-epoch>} to be
 * trusted, which it never will, and its contents are never attacker-chosen or readable — so the payload-format
 * re-check provides logical domain separation. AAD binding would add cryptographic domain separation but would
 * require widening the {@code SecretCipher} seam; the format check is sufficient here.
 */
public final class TrustedDeviceService implements TrustedDeviceIssuer {

    /** The login-form field set when the user ticks "remember this device". Presence = opted in. */
    static final String REMEMBER_PARAM = "remember-device";
    /** Cookie name prefix; the app realm uses {@code runway_td_app}, a tenant uses {@code runway_td_site_<siteId>}. */
    private static final String COOKIE_PREFIX = "runway_td_";
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;

    private final ProtectionProperties properties;
    private final ProtectionConfigStore configStore;
    private final SecretCipher cipher;

    public TrustedDeviceService(ProtectionProperties properties, ProtectionConfigStore configStore,
                                SecretCipher cipher) {
        this.properties = properties;
        this.configStore = configStore;
        this.cipher = cipher;
    }

    /** Whether the feature is usable at all: enabled by config AND a master key is configured to sign the cookie. */
    public boolean isEnabled() {
        return properties.getRememberDevice().isEnabled() && cipher.isAvailable();
    }

    /** The configured trust window, clamped to a sane range. */
    public int rememberDays() {
        int days = properties.getRememberDevice().getDays();
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    @Override
    public void rememberIfRequested(HttpServletRequest request, HttpServletResponse response, ProtectionScope scope) {
        if (!isEnabled() || unscopedTenant(scope)) {
            return;
        }
        if (request.getParameter(REMEMBER_PARAM) == null) {
            return; // the "remember this device" box was not ticked
        }
        // Only mint a cookie when the scope actually has a CAPTCHA to skip — otherwise it would do nothing.
        if (!configStore.resolve(scope).captcha().active()) {
            return;
        }
        long expiry = System.currentTimeMillis() + (long) rememberDays() * 86_400_000L;
        String sealed;
        try {
            sealed = cipher.encrypt(scope.realm().name() + '|' + siteIdOf(scope) + '|' + expiry);
        } catch (RuntimeException e) {
            return; // cipher fault — degrade to no cookie (CAPTCHA stays shown)
        }
        ResponseCookie cookie = ResponseCookie.from(cookieName(scope), sealed)
                .path("/")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .maxAge(Duration.ofDays(rememberDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Whether THIS request carries a valid trusted-device cookie for {@code scope} (so the CAPTCHA may be skipped).
     * Fail-safe: any absence/forgery/tamper/scope-mismatch/expiry returns {@code false} (CAPTCHA shown).
     */
    public boolean isTrusted(HttpServletRequest request, ProtectionScope scope) {
        if (!isEnabled() || unscopedTenant(scope)) {
            return false;
        }
        String raw = readCookie(request, cookieName(scope));
        // Reject a value that is not our sealed ENC(...) form: SecretCipher.decrypt passes a non-tagged value
        // through unchanged, so without this a client could forge a plaintext "APPLICATION||<future>" cookie.
        if (raw == null || !cipher.isEncrypted(raw)) {
            return false;
        }
        String payload;
        try {
            payload = cipher.decrypt(raw); // GCM auth failure on any tamper ⇒ throws
        } catch (RuntimeException e) {
            return false;
        }
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3
                || !parts[0].equals(scope.realm().name())
                || !parts[1].equals(siteIdOf(scope))) {
            return false;
        }
        try {
            return Long.parseLong(parts[2]) > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A tenant scope with no resolved site can't be keyed safely — decline to issue/trust. */
    private static boolean unscopedTenant(ProtectionScope scope) {
        return scope.realm() == ProtectionScope.Realm.TENANT && scope.resolvedSiteId().isEmpty();
    }

    private static String siteIdOf(ProtectionScope scope) {
        return scope.siteId() == null ? "" : scope.siteId();
    }

    private static String cookieName(ProtectionScope scope) {
        // Disjoint namespaces: the app realm is the fixed "app"; a tenant is "site_<id>". A site id cannot be
        // "app" after the "site_" prefix, so a tenant whose id is literally "app" can no longer collide with the
        // app-realm cookie on a shared host (they'd otherwise overwrite each other — a cross-realm papercut, though
        // the payload realm binding already prevents any actual cross-realm trust).
        return COOKIE_PREFIX + (scope.realm() == ProtectionScope.Realm.APPLICATION ? "app" : "site_" + scope.siteId());
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
