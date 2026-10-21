package mx.quest.runway.spi;

import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;

/**
 * A renderable piece of body content a page or master pulls in at an anchor — a carousel, a
 * search box, a recent-posts list. The host finds the anchor in the page markup (a
 * {@code data-widget="<plugin>/<instance>"} attribute), resolves the instance's configuration,
 * and asks the matching widget to render.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #render(WidgetView)} is a <b>pure function</b> of its {@link WidgetView};
 *       implementations are <b>stateless and thread-safe</b>.</li>
 *   <li><b>Soft-degrade:</b> when there is nothing to show (missing instance, disabled
 *       feature), return {@link RenderFragment#empty()} — the page still renders, just without
 *       this widget. Never throw to signal "nothing to show".</li>
 *   <li>{@link #key()} is the stable widget-type key referenced by the {@code data-widget}
 *       attribute; a duplicate among kernel contributions fails startup; a plugin's
 *       colliding contribution is refused (the later plugin's in load order) and reported (since SPI 0.4.0).</li>
 * </ul>
 *
 * <h2>Limits &amp; failure</h2>
 * A {@code render} that throws a {@code RuntimeException} or {@code LinkageError}, or returns {@code null}, renders
 * as {@link RenderFragment#empty()}; the page carrying the hole is served {@code Cache-Control: no-store} without
 * validators. The failure is logged (with the stack the first time, then at most once a minute), counted in
 * {@code runway.plugin.contribution.failures} and shown on the plugins screen.
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface ContentWidget {

    /**
     * This widget type's stable, unique key — the {@code <type>} half of a
     * {@code data-widget="<type>/<instance>"} anchor.
     *
     * @return a non-{@code null}, non-empty identifier, stable across releases
     */
    String key();

    /**
     * Renders this widget for the given page/instance context.
     *
     * @param view the immutable, host-materialized context including the instance config
     *             (never {@code null})
     * @return the fragment to splice at the anchor; {@link RenderFragment#empty()} to
     *         soft-degrade. Never {@code null}.
     */
    RenderFragment render(WidgetView view);

    /**
     * The capabilities this widget declares (for example whether it is configurable), so the
     * host can branch on data rather than type. Defaults to {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }
}
