package mx.quest.runway.spi.model;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable input to a {@link mx.quest.runway.spi.ContentWidget} — the page/instance
 * context plus the widget instance's resolved configuration. The host locates the anchor in
 * the page markup (a {@code data-widget="<plugin>/<instance>"} attribute), resolves the
 * instance's config, and hands the widget this frozen view.
 *
 * @param site       the site being served (never {@code null})
 * @param page       the page the widget appears on (never {@code null})
 * @param locale     the resolved request locale (never {@code null})
 * @param instanceId the widget instance identifier from the anchor (never {@code null}); lets
 *                   one widget type render differently per placement
 * @param isChrome   whether the anchor is in the site's master chrome (so the widget shows on
 *                   every page of that master) rather than in a single page's content — it
 *                   affects rendering scope and cache-invalidation reasoning
 * @param config     the resolved configuration for this instance (never {@code null};
 *                   defensively copied, unmodifiable; may be empty). Absent keys mean "use the
 *                   widget's default". The host guarantees (since SPI 0.4.0) that no key or value is
 *                   {@code null} at any depth and that nested maps and lists are unmodifiable too, so a
 *                   widget may cache or share what it reads without copying.
 * @param query      the request's query parameters (first value per name), so a widget can render
 *                   request-dependent output on a normal page — a search-results widget reading
 *                   {@code ?q=}, a form widget reading a {@code ?sent} confirmation flag. Never
 *                   {@code null} (empty when there is no query); defensively copied, unmodifiable.
 *                   The widget stays a <b>pure function</b> of this view: it may READ the query to
 *                   choose what to render, but must not mutate anything (side effects — storing a
 *                   submission, sending mail — belong to a request-handling endpoint, not to render).
 * @param path       the public request path being rendered, exactly as served (locale prefix included — e.g.
 *                   {@code /es/blog/mi-entrada}); empty when unknown (a bare preview). A widget that needs
 *                   "the current page's URL" (a return path, a self-link) MUST use this — never build one from
 *                   {@link PageMeta#slug()}, which for a content source's page is a composite identity, not a
 *                   path.
 */
public record WidgetView(SiteView site, PageMeta page, Locale locale, String instanceId,
                         boolean isChrome, Map<String, Object> config, Map<String, String> query,
                         String path) {

    public WidgetView {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(instanceId, "instanceId");
        config = (config == null) ? Map.of() : Map.copyOf(config);
        query = (query == null) ? Map.of() : Map.copyOf(query);
        path = (path == null) ? "" : path;
    }

    /**
     * The pre-{@code path} constructor, kept for binary compatibility so a widget or host compiled against an
     * earlier SPI keeps loading on this one. Materializes an empty path.
     */
    public WidgetView(SiteView site, PageMeta page, Locale locale, String instanceId,
                      boolean isChrome, Map<String, Object> config, Map<String, String> query) {
        this(site, page, locale, instanceId, isChrome, config, query, "");
    }

    /**
     * The pre-{@code query} constructor, kept for binary compatibility so a widget or host compiled against an
     * earlier SPI keeps loading on this one. Materializes an empty query.
     */
    public WidgetView(SiteView site, PageMeta page, Locale locale, String instanceId,
                      boolean isChrome, Map<String, Object> config) {
        this(site, page, locale, instanceId, isChrome, config, Map.of(), "");
    }
}
