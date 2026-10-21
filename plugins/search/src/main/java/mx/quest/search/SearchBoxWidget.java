package mx.quest.search;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;

/**
 * The {@code search} content widget: a site manager drops a search box into any page (or the master chrome) with
 * {@code <div id="x" data-widget="search/box"></div>} and the host renders the anchor. It emits a small GET form
 * that submits {@code ?q=…} to the site's <b>results page</b> (the {@code resultsPath} setting, default
 * {@code /search} — a normal, editable tenant page the operator installs from the settings, carrying this same
 * widget). The widget is <b>query-aware</b> (kernel {@code WidgetView.query()}): when the page it sits on is
 * requested with a non-blank {@code q}, it renders the results <b>inline below the box</b>, so the results page is
 * an ordinary, restyleable site page rather than a plugin-owned surface. On a page without {@code q} (e.g. a home
 * page) it renders just the box. A box in the master chrome (a header or footer) renders just the box by default, so
 * the results page does not list its results twice; the per-placement {@code results} setting overrides that
 * ({@link #listsResults}). Each result link carries the words the engine matched ({@link HighlightMarker}), which
 * the page it opens highlights, unless the site turned {@code highlight} off.
 *
 * <p>Honors the per-tenant {@code enabled} flag (disabled ⇒ {@link RenderFragment#empty()}) and the
 * {@code placeholder}/{@code resultLimit} settings (this plugin's {@link PluginSettings}); its words come from
 * {@link Messages#site} for the site and the request locale (the site's own bundle first, then this plugin's). Titles and the query are HTML-escaped by {@link Html}; each hit's snippet is pre-escaped,
 * {@code <mark>}-bearing HTML (the frozen {@link SearchHit} contract) emitted RAW. Engine selection mirrors the
 * endpoint's: the highest-priority <b>available</b> {@link SiteSearchEngine} (so {@code search-lucene} takes over
 * transparently). Rendering results during page render carries no per-client rate limiter (a {@code WidgetView} has no
 * client IP); a query flood is bounded by the kernel's edge protection and by {@code resultLimit}. The JSON API
 * ({@code GET /search/api}) keeps its dedicated {@link SearchRateLimiter}.
 *
 * <p>A query that finds nothing is handed to {@link NoResultsLog#record}, which does nothing unless the site turned
 * recording on.
 */
public class SearchBoxWidget implements ContentWidget {

    /** The per-placement setting ({@code widgetSettings} in plugin.yml) choosing whether this box lists results. */
    static final String RESULTS_SETTING = "results";
    static final String RESULTS_AUTO = "auto";

    private final List<SiteSearchEngine> engines;
    private final PluginSettings settings;
    private final Messages messages;
    private final NoResultsLog noResults;
    /** Null in tests that do not exercise the localized action: the box then submits to the setting as written. */
    private final ResultsPageLinks resultsPages;

    /**
     * @param noResults where a query that found nothing is recorded; {@code null} records nothing
     */
    public SearchBoxWidget(List<SiteSearchEngine> engines, PluginSettings settings, Messages messages,
                           NoResultsLog noResults) {
        this(engines, settings, messages, noResults, null);
    }

    SearchBoxWidget(List<SiteSearchEngine> engines, PluginSettings settings, Messages messages,
                    NoResultsLog noResults, ResultsPageLinks resultsPages) {
        this.engines = List.copyOf(engines);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.noResults = noResults;
        this.resultsPages = resultsPages;
    }

    @Override
    public String key() {
        return "search";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        String siteId = view.site().id();
        SearchSiteConfig cfg = SearchSiteConfig.of(settings.site(siteId));
        if (!cfg.enabled()) {
            return RenderFragment.empty();
        }
        Locale locale = view.locale();
        String placeholder = cfg.placeholder(msg(siteId, "search.box.placeholder", "Search…", locale));
        String submit = msg(siteId, "search.box.submit", "Search", locale);
        // The results page in this page's language (2.2.0): a box on /es/… submits to the Spanish results.
        String action = resultsPages == null ? cfg.resultsPath()
                : resultsPages.pathFor(siteId, cfg.resultsPath(), locale);
        String query = view.query().getOrDefault("q", "").strip();

        StringBuilder b = new StringBuilder(512);
        b.append("<link rel=\"stylesheet\" href=\"/__plugin/search/static/search.css")
                .append(SearchConfig.ASSET_QUERY).append("\">")
                .append("<form class=\"rw-search-box\" method=\"get\" action=\"").append(Html.escape(action))
                .append("\" role=\"search\">")
                .append("<input class=\"rw-search-box-input\" type=\"search\" name=\"q\" value=\"")
                .append(Html.escape(query)).append("\" placeholder=\"").append(Html.escape(placeholder))
                .append("\" aria-label=\"").append(Html.escape(placeholder)).append("\">")
                .append("<button class=\"rw-search-box-submit\" type=\"submit\">").append(Html.escape(submit))
                .append("</button></form>");

        // Query-aware: when this page is requested with ?q=…, render the results below the box (the results page
        // is a normal editable page carrying this widget), so there is no plugin-owned results surface.
        if (!query.isBlank() && listsResults(view)) {
            List<SearchHit> hits = search(siteId, query, locale, cfg.resultLimit());
            if (hits.isEmpty() && noResults != null) {
                noResults.record(siteId, query); // a content gap (a no-op unless the site opted in)
            }
            b.append(renderResults(siteId, query, hits, locale, cfg.highlight()));
        }
        return RenderFragment.of(b.toString());
    }

    /**
     * Whether this placement lists results when its page carries {@code ?q=} — the placement's {@code results}
     * setting (since 2.1.0): {@code always}, {@code never}, or {@code auto} (the default), which lists them unless the
     * box sits in the master chrome. A header or footer box is in the chrome and appears on the results page too;
     * before 2.1.0 it listed the results a second time there.
     */
    static boolean listsResults(WidgetView view) {
        Object setting = view.config().get(RESULTS_SETTING);
        String mode = setting == null ? RESULTS_AUTO : setting.toString().strip().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "always" -> true;
            case "never" -> false;
            default -> !view.isChrome();
        };
    }

    /** The results block (count/empty + hit list) rendered inline below the box; empty string on a blank query. */
    private String renderResults(String siteId, String query, List<SearchHit> hits, Locale locale,
                                 boolean highlight) {
        StringBuilder b = new StringBuilder(512);
        if (hits.isEmpty()) {
            return b.append("<p class=\"rw-search-empty\">")
                    .append(Html.escape(msg(siteId, "search.results.none", "No results for", locale)))
                    .append(" “").append(Html.escape(query)).append("”.</p>").toString();
        }
        b.append("<p class=\"rw-search-count\">").append(hits.size()).append(' ')
                .append(Html.escape(msg(siteId, "search.results.count", "result(s) for", locale)))
                .append(" “").append(Html.escape(query)).append("”</p><ol class=\"rw-search-hits\">");
        for (SearchHit hit : hits) {
            b.append("<li class=\"rw-search-hit\"><a class=\"rw-search-hit-link\" href=\"")
                    .append(Html.escape(highlight ? HighlightMarker.link(hit) : hit.url())).append("\">")
                    .append(Html.escape(hit.title())).append("</a>");
            if (!hit.snippet().isBlank()) {
                // Pre-escaped, <mark>-bearing HTML (frozen SearchHit contract): emit RAW so the highlight shows as
                // emphasis, not literal &lt;mark&gt;. XSS-safe because the engine escaped every source character.
                b.append("<p class=\"rw-search-hit-snippet\">").append(hit.snippet()).append("</p>");
            }
            b.append("</li>");
        }
        return b.append("</ol>").toString();
    }

    /** Runs the query through the highest-priority available engine (empty on a blank query or no engine). */
    private List<SearchHit> search(String siteId, String query, Locale locale, int limit) {
        SiteSearchEngine engine = engines.stream()
                .filter(e -> e.available(siteId))
                .max(Comparator.comparingInt(SiteSearchEngine::priority))
                .orElse(null);
        return engine == null ? List.of() : engine.search(new SearchQuery(siteId, query, locale, limit));
    }

    private String msg(String siteId, String key, String fallback, Locale locale) {
        return messages.site(siteId, locale, key, fallback);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("widget.search");
    }
}
