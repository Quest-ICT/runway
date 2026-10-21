package mx.quest.runway.spi.model;

import java.util.Objects;

/**
 * One route an {@link mx.quest.runway.spi.EndpointContributor} declares it will serve. The
 * host registers routes at load time, checking for collisions and refusing any that shadow a
 * reserved kernel prefix; each route carries the {@link SurfacePosture} the host applies to
 * its responses.
 *
 * <p><b>What the host serves</b> (enforced at registration since SPI 0.4.0; a plugin route that breaks a rule is
 * refused on its own, with a logged reason, while the plugin's other routes load):
 * <ul>
 *   <li>{@code pathPattern} is an <b>exact</b>, absolute path, matched literally. The characters {@code *},
 *       <code>{</code> and <code>}</code> are <b>reserved</b> for a future pattern syntax and refused now, so that a
 *       route written today can never change meaning later.</li>
 *   <li>{@code realm} must be {@link Realm#TENANT}: endpoints are dispatched on a site's public host only.</li>
 *   <li>An {@code anonymous} route must use {@code POST} (since SPI 0.5.0).</li>
 * </ul>
 *
 * <h2>Anonymous form routes (since SPI 0.5.0)</h2>
 * Every {@code POST} on a site's host needs a CSRF token from the visitor's session — the defence against another
 * web site making a visitor's browser submit a form on their behalf. A public form placed on a cacheable page
 * (a contact form) cannot carry one: the page is shared by every visitor, and an anonymous visitor has no session.
 * A route declared {@code anonymous} opts out of the CSRF check, and the host makes that safe by taking away
 * everything such a forged submission could use:
 * <ul>
 *   <li>it is dispatched <b>with no principal</b>, even when the visitor is signed in: an
 *       {@link mx.quest.runway.spi.service.AccessCheck} answers {@code false}, so the route cannot perform anything
 *       a signed-in user could and an anonymous visitor could not;</li>
 *   <li>it accepts only {@code POST} with an {@code application/x-www-form-urlencoded} body (any other content type
 *       is answered {@code 415}); the fields arrive in {@link RouteRequest#form()};</li>
 *   <li>the exemption covers exactly that method and path on a site's host, and nothing else.</li>
 * </ul>
 * Use it only for a form any visitor may submit with no account (contact, newsletter sign-up), and keep the
 * form's own abuse guards (a CAPTCHA, a honeypot field, a rate limit by {@link RouteRequest#clientIp()}). Never use
 * it for an action that changes anything a signed-in user owns.
 *
 * @param method      the HTTP method (for example {@code "GET"}; never {@code null})
 * @param pathPattern the exact path this route matches (never {@code null}; see above)
 * @param realm       which realm the route belongs to (never {@code null}; must be {@code TENANT})
 * @param posture     how the route's responses are treated for indexing/sitemap/cache
 *                    ({@code null} normalized to {@link SurfacePosture#safeDefault()})
 * @param anonymous   whether this is an anonymous form route: CSRF-exempt, dispatched with no principal, form
 *                    body only (see above; since SPI 0.5.0)
 */
public record Route(String method, String pathPattern, Realm realm, SurfacePosture posture, boolean anonymous) {

    public Route {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathPattern, "pathPattern");
        Objects.requireNonNull(realm, "realm");
        posture = (posture == null) ? SurfacePosture.safeDefault() : posture;
    }

    /** The pre-0.5.0 form: an ordinary (not anonymous) route. */
    public Route(String method, String pathPattern, Realm realm, SurfacePosture posture) {
        this(method, pathPattern, realm, posture, false);
    }

    /**
     * An anonymous form route ({@code POST}, tenant realm): CSRF-exempt, dispatched with no principal, accepting
     * only an {@code application/x-www-form-urlencoded} body.
     *
     * @since SPI 0.5.0
     */
    public static Route anonymousPost(String pathPattern, SurfacePosture posture) {
        return new Route("POST", pathPattern, Realm.TENANT, posture, true);
    }

    /** A {@code GET} route in the given realm with the fail-safe {@link SurfacePosture}. */
    public static Route get(String pathPattern, Realm realm) {
        return new Route("GET", pathPattern, realm, SurfacePosture.safeDefault());
    }
}
