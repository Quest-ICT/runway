package mx.quest.runway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.ClientIp;
import mx.quest.runway.web.RequestPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The kernel login brute-force shell: added <b>before</b> {@code UsernamePasswordAuthenticationFilter} in each
 * realm's security chain, it runs two pre-authentication checks on a {@code POST} to that realm's login path,
 * both before the password is ever verified (sparing the Argon2 hash cost against a blocked attacker):
 *
 * <ol>
 *   <li><b>Per-IP lockout</b> ({@link LoginAttemptGate}) — when the gated client IP ({@link ClientIp#of}) is
 *       locked out, redirect to {@code <loginPath>?locked}.</li>
 *   <li><b>Request-aware form guard</b> ({@link LoginFormGuard}) — the honeypot trap, and CAPTCHA. A non-OK
 *       verdict <b>records a failed attempt</b> (so a bot spamming the trap still walks itself
 *       into the IP lockout) and redirects to {@code <loginPath>?<flag>} — {@code ?error} for the honeypot, so a
 *       bot cannot tell the trap tripped.</li>
 * </ol>
 *
 * <p>The login path is compared with both spellings of the request path ({@link RequestPaths#eitherMatches}):
 * Spring Security processes a sign-in posted to {@code /app/%6Cogin} exactly like one posted to
 * {@code /app/login}, so a raw-URI comparison would let an encoded spelling sign in past the lockout and the
 * form guard.
 *
 * <p>Any other request passes straight through. One instance per realm, constructed with that realm's login path
 * and {@link ProtectionScope.Realm}: the app-realm shell guards {@code /app/login} with an application scope, the
 * tenant-realm shell guards {@code /login} with a per-site scope derived from {@link SiteResolver#current()} at
 * request time (so a per-site protection config governs the site's own login). It is <b>not</b> added to the
 * wizard chain (pre-setup has no realm). With the kernel's permissive defaults neither check ever fires; the
 * lockout and honeypot policy arrive with the essential protection plugin.
 *
 * <p><b>Renaming this class is a breaking monitoring change.</b> Spring Security names one meter per chain
 * filter after the filter class, so this name is published as
 * {@code spring_security_filterchains_LoginProtectionFilter_{before,after}_total}. A rename silently renames those
 * production metrics — dashboards lose their series and alert rules stop matching, with no compiler error and
 * nothing in review to catch it. {@code SecurityFilterMetricNamesTest} pins the name; if you rename this,
 * update that literal and tell whoever owns the monitoring stack.
 */
public class LoginProtectionFilter extends OncePerRequestFilter {

    private final String loginPath;
    private final LoginAttemptGate gate;
    private final LoginFormGuard formGuard;
    private final ProtectionScope.Realm realm;
    private final SiteResolver siteResolver;
    private final boolean enforceLockout;

    /**
     * A full login shell — the per-IP lockout <b>and</b> the honeypot/CAPTCHA form guard.
     *
     * @param loginPath    the realm's login path this shell guards (e.g. {@code /app/login} or {@code /login})
     * @param gate         the per-IP lockout seam
     * @param formGuard    the request-aware honeypot/CAPTCHA seam
     * @param realm        the realm this shell belongs to (selects the {@link ProtectionScope})
     * @param siteResolver used only for {@link ProtectionScope.Realm#TENANT} to bind the current site into the
     *                     scope; may be {@code null} for an {@link ProtectionScope.Realm#APPLICATION} shell
     */
    public LoginProtectionFilter(String loginPath, LoginAttemptGate gate, LoginFormGuard formGuard,
                                 ProtectionScope.Realm realm, SiteResolver siteResolver) {
        this(loginPath, gate, formGuard, realm, siteResolver, true);
    }

    /**
     * A shell with an optional lockout. Pass {@code enforceLockout=false} to guard a form with the honeypot/CAPTCHA
     * check <b>only</b> — used for the <b>password-recovery request</b> form ({@code /app/forgot-password} and
     * {@code /forgot-password}): that form must deter bots (a CAPTCHA stops automated reset-email flooding) but must
     * <b>not</b> ride the per-IP login lockout, because a user locked out of the login is exactly who needs the
     * recovery form to work. With {@code enforceLockout=false} the {@code gate} is unused and a failed check just
     * redirects to {@code <path>?<flag>} without recording a lockout failure.
     */
    public LoginProtectionFilter(String loginPath, LoginAttemptGate gate, LoginFormGuard formGuard,
                                 ProtectionScope.Realm realm, SiteResolver siteResolver, boolean enforceLockout) {
        this.loginPath = loginPath;
        this.gate = gate;
        this.formGuard = formGuard;
        this.realm = realm;
        this.siteResolver = siteResolver;
        this.enforceLockout = enforceLockout;
    }

    /**
     * A per-<b>path</b> once-key. Two {@code LoginProtectionFilter} instances now coexist in one chain (the login
     * shell and the recovery-form shell); {@link OncePerRequestFilter}'s default key is the class name, so the
     * first instance's "already filtered" flag would suppress {@link #doFilterInternal} on the second — and only
     * the login shell would ever run (the recovery form would silently go unguarded). Keying by the guarded path
     * makes each instance dedup independently.
     */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return getClass().getName() + '.' + loginPath + ".FILTERED";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && RequestPaths.eitherMatches(request, loginPath::equals)) {
            String clientIp = enforceLockout ? ClientIp.of(request) : null;
            if (enforceLockout && gate.isLocked(clientIp)) {
                response.sendRedirect(loginPath + "?locked");
                return;
            }
            LoginFormVerdict verdict = formGuard.check(request, scope());
            if (!verdict.allowed()) {
                // A tripped deterrent counts toward the IP lockout (login only), so a bot hammering the trap locks
                // itself out — but the recovery form never records a lockout (see the enforceLockout javadoc).
                if (enforceLockout) {
                    gate.recordFailure(clientIp);
                }
                response.sendRedirect(loginPath + "?" + verdict.redirectFlag());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The scope for the current request. Application shells are always {@link ProtectionScope#application()}; a
     * tenant shell binds the site {@link SiteResolver#current() currently resolved} for the request (empty → a
     * site-less tenant scope, which the guard degrades to its defaults — an unresolvable site never blocks login).
     */
    private ProtectionScope scope() {
        if (realm == ProtectionScope.Realm.APPLICATION) {
            return ProtectionScope.application();
        }
        String siteId = siteResolver != null
                ? siteResolver.current().map(mx.quest.runway.site.Site::id).orElse(null)
                : null;
        return ProtectionScope.forSite(siteId);
    }
}
