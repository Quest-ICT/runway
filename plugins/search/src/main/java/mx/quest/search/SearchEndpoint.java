package mx.quest.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The search plugin's request surface — it owns <b>no visible page</b>. The search box + inline results are a
 * query-aware {@link SearchBoxWidget} on a normal, editable tenant page (the operator installs the ready-made
 * results page from the settings). This endpoint only:
 * <ul>
 *   <li><b>{@code GET /search/api?q=…}</b> — results as JSON, for a site's own scripts / an autocomplete box.
 *       Picks the highest-priority <b>available</b> {@link SiteSearchEngine} (so {@code search-lucene} takes over
 *       transparently). {@code noindex} (fail-safe posture) and rate-limited per client by
 *       {@link SearchRateLimiter}, keyed on the client IP the kernel resolved behind its trusted-proxy gate
 *       ({@link RouteRequest#clientIp()}). A query that finds nothing is handed to {@link NoResultsLog#record} (a
 *       no-op unless the site opted in).</li>
 *   <li><b>{@code POST /search/reindex}</b> — rebuilds this site's index on <b>every</b> registered engine via
 *       {@link SiteSearchEngine#reindex} (the built-in and any contributed engine alike).</li>
 *   <li><b>{@code POST /search/install-page}</b> — installs the ready-made, editable results page at
 *       {@code /search} carrying the search-box widget through {@link SitePages#install}, which follows the site's
 *       editorial level and never overwrites; the operator is told which outcome happened. It is created
 *       {@code noindex} (see {@link #handleInstallPage}).</li>
 *   <li><b>{@code POST /search/no-results/clear}</b> — forgets the site's recorded no-result terms
 *       ({@link NoResultsLog}), then redirects back to the settings page.</li>
 * </ul>
 *
 * <h2>The three operator actions are CSRF-checked {@code POST}s</h2>
 * Each changes state (an index is rebuilt, a page is created, data is deleted), so each is an ordinary,
 * <b>non-anonymous</b> {@code POST} route: the kernel's tenant chain demands the console session's CSRF token (the
 * admin fragment's forms carry it), which is what stops another web site from triggering them through a signed-in
 * operator's browser with a link or an {@code <img>}. A {@code GET} on any of them matches no route (the kernel
 * answers 404) and {@link #handle} refuses a wrong method too, as a second line. Each is gated by the
 * {@code search|settings} control point through {@link AccessCheck} and fails <b>closed</b> ({@code 404}) for
 * anyone who lacks it. Their answers are console text, in the operator's language ({@link Messages#console}).
 *
 * <p>The public API honours the per-tenant {@code enabled} flag (disabled ⇒ {@code 404}). The JSON snippet is
 * rendered per the frozen {@link SearchHit} contract: the query is escaped by the JSON writer and the pre-escaped,
 * {@code <mark>}-bearing snippet travels verbatim as a JSON string.
 */
public class SearchEndpoint implements EndpointContributor {

    private static final Logger log = LoggerFactory.getLogger(SearchEndpoint.class);

    static final String PLUGIN_ID = "search";
    /** The control point that gates the maintenance actions (declared in {@code plugin.yml}). */
    static final String SETTINGS_PERMISSION = "search|settings";
    /** The slug/path the ready-made, editable results page is installed at (then owned + movable by the operator). */
    static final String INSTALLED_PAGE_SLUG = "search";
    static final String API_PATH = "/search/api";
    static final String REINDEX_PATH = "/search/reindex";
    static final String INSTALL_PATH = "/search/install-page";
    /** The POST action (from the admin fragment's CSRF-carrying form) that forgets the site's no-result terms. */
    static final String CLEAR_NO_RESULTS_PATH = "/search/no-results/clear";
    /** Where the clear action sends the operator back to: this plugin's per-tenant settings page. */
    static final String SETTINGS_PAGE = "/__admin/plugins/" + PLUGIN_ID + "/settings";

    private final List<SiteSearchEngine> engines;
    private final PluginSettings settings;
    private final Messages messages;
    private final SearchRateLimiter rateLimiter;
    private final AccessCheck access;
    private final SitePages pages;
    private final NoResultsLog noResults;

    public SearchEndpoint(List<SiteSearchEngine> engines, PluginSettings settings, Messages messages,
                          SearchRateLimiter rateLimiter, AccessCheck access, SitePages pages,
                          NoResultsLog noResults) {
        this.engines = List.copyOf(engines);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.noResults = Objects.requireNonNull(noResults, "noResults");
    }

    @Override
    public String key() {
        return "search/results";
    }

    @Override
    public List<Route> routes() {
        // No public /search PAGE — the box + inline results are a widget on a normal editable page. These are the
        // JSON API + the three operator actions; all safeDefault (noindex/no-store), never advertised or crawled.
        // The operator actions are POST and NOT anonymous: they change state, so the kernel's CSRF check applies.
        return List.of(
                new Route("GET", API_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                new Route("POST", REINDEX_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                new Route("POST", INSTALL_PATH, Realm.TENANT, SurfacePosture.safeDefault()),
                new Route("POST", CLEAR_NO_RESULTS_PATH, Realm.TENANT, SurfacePosture.safeDefault()));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        // The kernel dispatches by method + path; these method checks are the second line.
        boolean post = "POST".equalsIgnoreCase(req.method());
        return switch (req.path()) {
            case API_PATH -> "GET".equalsIgnoreCase(req.method()) ? handleApi(req) : RouteResponse.notFound();
            case REINDEX_PATH -> post ? handleReindex(req) : RouteResponse.notFound();
            case INSTALL_PATH -> post ? handleInstallPage(req) : RouteResponse.notFound();
            case CLEAR_NO_RESULTS_PATH -> post ? handleClearNoResults(req) : RouteResponse.notFound();
            default -> RouteResponse.notFound();
        };
    }

    // --- JSON API ----------------------------------------------------------------------------------

    private RouteResponse handleApi(RouteRequest req) {
        String siteId = req.site().id();
        SearchSiteConfig cfg = SearchSiteConfig.of(settings.site(siteId));
        if (!cfg.enabled()) {
            return RouteResponse.notFound();
        }
        String query = req.query().getOrDefault("q", List.of()).stream().findFirst().orElse("").strip();
        if (!query.isBlank() && !rateLimiter.tryAcquire(SearchClientId.of(req))) {
            return json(429, "{\"error\":\"rate_limited\"}");
        }
        List<SearchHit> hits = search(siteId, query, req.locale(), cfg.resultLimit());
        if (hits.isEmpty() && !query.isBlank()) {
            noResults.record(siteId, query); // a content gap (a no-op unless the site opted in)
        }
        return json(200, toJson(query, hits, cfg.highlight()));
    }

    /**
     * Serializes results as JSON with a hand-rolled, correctly-escaped writer (no Jackson dependency): every
     * string value is JSON-escaped, and the snippet — pre-escaped HTML with {@code <mark>} — travels verbatim as
     * a JSON string, so a consumer can insert it as HTML on its side under the same contract. Since 2.1.0 each
     * result also has {@code terms} (the matched words, plain text) and {@code link}: the URL with the highlight
     * marker ({@link HighlightMarker}), or the plain URL when the site turned {@code highlight} off.
     */
    static String toJson(String query, List<SearchHit> hits, boolean highlight) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"query\":").append(jsonString(query)).append(",\"count\":").append(hits.size())
                .append(",\"results\":[");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"url\":").append(jsonString(hit.url()))
                    .append(",\"title\":").append(jsonString(hit.title()))
                    .append(",\"snippet\":").append(jsonString(hit.snippet()))
                    .append(",\"score\":").append(hit.score())
                    .append(",\"link\":").append(jsonString(highlight ? HighlightMarker.link(hit) : hit.url()))
                    .append(",\"terms\":[");
            for (int t = 0; t < hit.terms().size(); t++) {
                b.append(t > 0 ? "," : "").append(jsonString(hit.terms().get(t)));
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    // --- maintenance: reindex ----------------------------------------------------------------------

    /**
     * Rebuilds this site's index on <b>every</b> registered engine via {@link SiteSearchEngine#reindex} — not
     * only the built-in one, so a contributed engine (e.g. {@code search-lucene}) is rebuilt by the same button.
     * The reported count is the fresh document count of the engine that actually serves this site's queries (the
     * highest-priority available one), since that is the index whose results the operator is looking at.
     */
    private RouteResponse handleReindex(RouteRequest req) {
        if (!access.may(SETTINGS_PERMISSION)) {
            return RouteResponse.notFound(); // fail closed: never reveal the action to the unauthorized
        }
        String siteId = req.site().id();
        SiteSearchEngine active = pick(siteId);
        int total = 0;
        for (SiteSearchEngine engine : engines) {
            int documents = engine.reindex(siteId);
            if (engine == active) {
                total = documents;
            }
        }
        return RouteResponse.text(200, console("search.reindex.done", "Search index rebuilt.") + " (" + total + ")");
    }

    // --- maintenance: clear the no-result terms ----------------------------------------------------

    /**
     * Forgets every recorded no-result term for this site, then sends the operator back to the settings page
     * ({@code 303}).
     */
    private RouteResponse handleClearNoResults(RouteRequest req) {
        if (!access.may(SETTINGS_PERMISSION)) {
            return RouteResponse.notFound();
        }
        try {
            noResults.clear(req.site().id());
        } catch (IOException | RuntimeException e) {
            log.warn("could not clear the no-result search list for site '{}': {}", req.site().id(), e.toString());
            return RouteResponse.text(500, console("search.noresults.clearFailed",
                    "Could not clear the list of searches with no results."));
        }
        return new RouteResponse(303, Map.of("Location", SETTINGS_PAGE), new byte[0]);
    }

    // --- maintenance: install search landing page --------------------------------------------------

    /**
     * Installs a first-class, editable tenant page at {@code /search} carrying the query-aware search-box widget,
     * so the results page is a real, master-wrapped site page the operator owns. {@link SitePages#install} never
     * overwrites (an existing page is reported with where it lives now) and follows the site's editorial level. The
     * page is created {@code noindex}: a search-results page is the platform design docs' canonical index
     * opt-<b>out</b> (thin, duplicate, infinite crawl space engines discourage indexing); since the page is the
     * operator's own after install, the setting can still be flipped in the page meta.
     */
    private RouteResponse handleInstallPage(RouteRequest req) {
        if (!access.may(SETTINGS_PERMISSION)) {
            return RouteResponse.notFound();
        }
        String siteId = req.site().id();
        // The installed page is titled in the SITE default language (the page is site-default-only).
        Locale siteDefault = req.site().defaultLocale();
        String title = messages.site(siteId, siteDefault, "search.page.title", "Search");
        StarterPage page = new StarterPage(INSTALLED_PAGE_SLUG, "/" + INSTALLED_PAGE_SLUG,
                Map.of(siteDefault.getLanguage(), title), installedPageHtml(title), installedPageLogic(), true);
        InstallResult result;
        try {
            result = pages.install(siteId, page);
        } catch (RuntimeException e) {
            // IllegalStateException: a path another page serves, an unknown site, a failed write (no stray draft
            // is left behind). Anything else is equally "not installed" to the operator.
            log.warn("search page install failed for site '{}': {}", siteId, e.toString());
            return RouteResponse.text(500, console("search.page.failed", "Could not install the search page."));
        }
        return RouteResponse.text(200, describe(result));
    }

    /** The operator's words for each install outcome; an outcome added in a later SPI MINOR gets a neutral line. */
    private String describe(InstallResult result) {
        String path = result.path();
        return switch (result.outcome()) {
            case PUBLISHED -> console("search.page.installed", "Search page installed at") + " " + path;
            case PENDING_REVIEW -> console("search.page.pending",
                    "The search page was created and is waiting in the review queue:") + " " + path;
            case DRAFT_BLOCKED -> {
                StringBuilder text = new StringBuilder(console("search.page.blocked",
                        "The search page was created as a draft because the quality checks blocked publishing; "
                                + "fix it in the page editor:")).append(' ').append(path);
                for (Finding finding : result.blocking()) {
                    text.append("\n- ").append(finding.code()).append(": ").append(finding.message());
                }
                yield text.toString();
            }
            case ALREADY_EXISTS -> console("search.page.exists", "A search page already exists at") + " " + path;
            default -> console("search.page.other", "The search page install finished:") + " "
                    + result.outcome() + " " + path;
        };
    }

    /**
     * The body ({@code .html}) of the installed page: a normal tenant page with the master's chrome seams
     * ({@code #site-header}/{@code #site-footer}/{@code head}, filled by {@link #installedPageLogic()}) whose
     * {@code #content} carries the search-box widget anchor. The anchor bears both an {@code id} and
     * {@code data-widget="search/box"} on the same element (the widget-binding contract) so the host's widget
     * scanner renders the box in place.
     */
    static String installedPageHtml(String title) {
        String t = Html.escape(title);
        return "<!DOCTYPE html>\n<html>\n<head><title>" + t + "</title></head>\n<body>\n"
                + "<header id=\"site-header\"></header>\n<main id=\"content\">\n"
                + "  <h1 id=\"search-page-title\">" + t + "</h1>\n"
                + "  <div id=\"search-box\" data-widget=\"search/box\"></div>\n"
                + "</main>\n<footer id=\"site-footer\"></footer>\n</body>\n</html>\n";
    }

    /**
     * The decoupled logic ({@code .th.xml}) of the installed page: pulls the master's chrome via the
     * {@code ${master}} model variable (never a literal {@code __…__} name) and sets the page title.
     */
    static String installedPageLogic() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<thlogic>\n"
                + "    <attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>\n"
                + "    <attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/>\n"
                + "    <attr sel=\"#site-footer\" th:replace=\"~{${master} :: #site-footer}\"/>\n"
                + "    <attr sel=\"#search-page-title\" th:text=\"${pageTitle}\"/>\n"
                + "</thlogic>\n";
    }

    // --- engine selection --------------------------------------------------------------------------

    private List<SearchHit> search(String siteId, String query, Locale locale, int limit) {
        if (query.isBlank()) {
            return List.of();
        }
        SiteSearchEngine engine = pick(siteId);
        return engine == null ? List.of() : engine.search(new SearchQuery(siteId, query, locale, limit));
    }

    /** The highest-priority engine that can serve {@code siteId} right now; {@code null} if none can. */
    SiteSearchEngine pick(String siteId) {
        return engines.stream()
                .filter(e -> e.available(siteId))
                .max(Comparator.comparingInt(SiteSearchEngine::priority))
                .orElse(null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static RouteResponse json(int status, String body) {
        return new RouteResponse(status, Map.of("Content-Type", "application/json; charset=utf-8"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private String console(String key, String fallback) {
        return messages.console(key, fallback);
    }

    /** JSON-escapes a string into a quoted JSON literal (control chars, quotes, backslashes, and the angle brackets). */
    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder b = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                // Escape < and > with unicode escapes so a JSON string embedded in an HTML <script> block
                // can't be broken out of with a literal close tag; the snippet's own mark tag survives escaped.
                case '<' -> b.append("\\u003c");
                case '>' -> b.append("\\u003e");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "search.results");
    }
}
