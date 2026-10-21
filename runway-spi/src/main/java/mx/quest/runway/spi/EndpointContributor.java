package mx.quest.runway.spi;

import java.util.List;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;

/**
 * Adds server routes — {@code robots.txt}, {@code sitemap.xml}, {@code llms.txt},
 * {@code /search}, a contact endpoint. The host reads {@link #routes()} at load time, checks
 * for collisions, and refuses any route that shadows a reserved kernel prefix; matching
 * requests are then dispatched to {@link #handle(RouteRequest)}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #routes()} is declared once at load and must be stable for the life of the
 *       process; each route carries the {@link mx.quest.runway.spi.model.SurfacePosture} the
 *       host applies to its responses (fail-safe: {@code noindex}, not in sitemap, no-store).</li>
 *   <li>{@link #handle(RouteRequest)} is a <b>pure function</b> of its frozen
 *       {@link RouteRequest} — no servlet/Spring types reach it; implementations are
 *       <b>stateless and thread-safe</b>.</li>
 *   <li>{@link #key()} is a stable, unique identifier; a duplicate among kernel contributions fails startup; a plugin's
 *       colliding contribution is refused (the later plugin's in load order) and reported (since SPI 0.4.0).</li>
 * </ul>
 *
 * <h2>Limits &amp; failure</h2>
 * <ul>
 *   <li>A request body over the host's cap ({@code runway.endpoints.max-body-bytes}, 10 MiB by default) is answered
 *       {@code 413} without calling {@code handle}.</li>
 *   <li>A {@code handle} that throws a {@code RuntimeException} or {@code LinkageError}, or returns {@code null}, is
 *       answered {@code 500} with {@code Cache-Control: no-store}; logged and counted. Any other {@code Error}
 *       propagates.</li>
 *   <li>A route the host cannot serve is refused on its own at load: a realm other than {@code TENANT}, a path with
 *       {@code *}, <code>{</code> or <code>}</code>, a reserved prefix, or a collision. The plugin's other routes
 *       still load.</li>
 *   <li>A handler may have side effects (storing a submission, sending mail) — that is what endpoints are for — but
 *       must not retain the {@link RouteRequest} beyond the call.</li>
 * </ul>
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface EndpointContributor {

    /**
     * This contributor's stable, unique key (for diagnostics and collision detection).
     *
     * @return a non-{@code null}, non-empty identifier, stable across releases
     */
    String key();

    /**
     * The routes this contributor serves. Read once at load; collision- and reserved-prefix-
     * checked by the host.
     *
     * @return the declared routes (never {@code null}; may be empty)
     */
    List<Route> routes();

    /**
     * Handles a request matched to one of this contributor's {@link #routes()}.
     *
     * @param request the immutable, host-materialized request (never {@code null})
     * @return the response to send back (never {@code null})
     */
    RouteResponse handle(RouteRequest request);

    /**
     * The capabilities this contributor declares, so the host can branch on data rather than
     * type. Defaults to {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }
}
