package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static mx.quest.search.Fixtures.engine;
import static mx.quest.search.Fixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The search plugin's request surface — it owns no visible page (the box + inline results are the query-aware
 * {@link SearchBoxWidget}). Covered here: the routes (one GET read, three non-anonymous POST actions), the JSON API
 * (engine pick, escaping, the per-client rate limit keyed on the kernel-gated client IP), the gated reindex, and the
 * gated {@code /search/install-page} through {@code SitePages} with each install outcome reported.
 */
class SearchEndpointTest {

    private FakePluginSettings settings;
    private FakeSiteServices kernel;

    @BeforeEach
    void setUp() {
        settings = new FakePluginSettings().site("acme", Map.of("enabled", true));
        kernel = new FakeSiteServices().site(ACME);
    }

    private SearchEndpoint endpoint(SiteSearchEngine... engines) {
        return endpoint(Fixtures.unlimited(), engines);
    }

    private SearchEndpoint endpoint(SearchRateLimiter limiter, SiteSearchEngine... engines) {
        return new SearchEndpoint(List.of(engines), settings, kernel, limiter, kernel, kernel,
                new NoResultsLog(new Fixtures.NoFiles(), settings, Clock.systemUTC(), false));
    }

    private static SearchRateLimiter rateLimiter(int perMinute, int perHour) {
        SearchProperties.RateLimit cfg = new SearchProperties.RateLimit();
        cfg.setPerIpPerMinute(perMinute);
        cfg.setPerIpPerHour(perHour);
        return new SearchRateLimiter(cfg);
    }

    private static String body(RouteResponse res) {
        return new String(res.body(), StandardCharsets.UTF_8);
    }

    // --- routes ------------------------------------------------------------------------------------

    @Test
    void theApiIsTheOnlyGetAndEveryOperatorActionIsANonAnonymousPost() {
        List<Route> routes = endpoint(engine("search/simple", 0, true)).routes();
        assertEquals(List.of("GET /search/api", "POST /search/reindex", "POST /search/install-page",
                        "POST /search/no-results/clear"),
                routes.stream().map(r -> r.method() + " " + r.pathPattern()).toList());
        assertTrue(routes.stream().noneMatch(Route::anonymous),
                "no route is CSRF-exempt: the operator actions need the console session's token");
        assertTrue(routes.stream().noneMatch(r -> r.posture().indexable()), "never advertised or crawled");
    }

    @Test
    void theRetiredSearchPageRouteIsNotHandled() {
        assertEquals(404, endpoint(engine("search/simple", 0, true)).handle(request("GET", "/search", "x")).status());
    }

    @Test
    void theHighestPriorityAvailableEngineIsPicked() {
        SearchEndpoint ep = endpoint(
                engine("search/simple", 0, true),
                engine("search/lucene", 100, true),
                engine("search/broken", 200, false)); // higher priority but unavailable
        assertEquals("search/lucene", ep.pick("acme").key(),
                "the highest-priority AVAILABLE engine wins; an unavailable one is skipped");
    }

    // --- JSON API ----------------------------------------------------------------------------------

    @Test
    void theJsonApiReturnsResults() {
        SearchEndpoint ep = endpoint(engine("search/simple", 0, true,
                new SearchHit("/guide", "Runway Guide", "deploy <mark>runway</mark>", 3.5)));
        RouteResponse res = ep.handle(request("GET", "/search/api", "runway"));
        assertEquals(200, res.status());
        assertEquals("application/json; charset=utf-8", res.headers().get("Content-Type"));
        String json = body(res);
        assertTrue(json.contains("\"count\":1"), "the count is reported");
        assertTrue(json.contains("\"url\":\"/guide\""), "the hit url is present");
        assertTrue(json.contains("\"title\":\"Runway Guide\""), "the hit title is present");
        assertTrue(json.contains("\"query\":\"runway\""), "the query echoes back");
    }

    @Test
    void theJsonApiPassesTheResultLimitAndLocaleToTheEngine() {
        List<SearchQuery> seen = new ArrayList<>();
        SiteSearchEngine recording = new SiteSearchEngine() {
            @Override
            public String key() {
                return "rec";
            }

            @Override
            public List<SearchHit> search(SearchQuery q) {
                seen.add(q);
                return List.of();
            }
        };
        settings.site("acme", Map.of("resultLimit", 7));
        RouteRequest es = new RouteRequest(ACME, Locale.of("es"), "GET", "/search/api", Map.of("q", List.of(" x ")),
                Map.of(), new byte[0]);
        endpoint(recording).handle(es);
        assertEquals(1, seen.size());
        assertEquals(new SearchQuery("acme", "x", Locale.of("es"), 7), seen.get(0), "trimmed text, limit, locale");
    }

    @Test
    void theJsonApiEscapesAngleBracketsInStrings() {
        SearchEndpoint ep = endpoint(engine("search/simple", 0, true, new SearchHit("/g", "T", "<mark>hi</mark>", 1)));
        String json = body(ep.handle(request("GET", "/search/api", "hi")));
        assertFalse(json.contains("<mark>"), "angle brackets are unicode-escaped in JSON output");
        assertTrue(json.contains("\\u003cmark\\u003e"), "…as \\u003c / \\u003e");
    }

    @Test
    void theJsonApiDisabledReturns404() {
        settings.site("acme", Map.of("enabled", false));
        assertEquals(404, endpoint(engine("search/simple", 0, true)).handle(request("GET", "/search/api", "x")).status());
    }

    @Test
    void theJsonApiIsReadOnlyByGet() {
        assertEquals(404, endpoint(engine("search/simple", 0, true)).handle(request("POST", "/search/api", "x"))
                .status());
    }

    @Test
    void theJsonApiIsRateLimitedPerTrustedClientIp() {
        SearchEndpoint ep = endpoint(rateLimiter(1, 1000), engine("search/simple", 0, true));
        assertEquals(200, ep.handle(request("GET", "/search/api", "x", "198.51.100.1")).status());
        assertEquals(429, ep.handle(request("GET", "/search/api", "x", "198.51.100.1")).status(),
                "the second query from the same client is over the per-minute budget");
        assertEquals(200, ep.handle(request("GET", "/search/api", "x", "198.51.100.2")).status(),
                "another client has its own budget");
    }

    @Test
    void aForwardedHeaderCannotPickANewRateLimitKey() {
        // 1.x keyed on the first X-Forwarded-For hop, so a visitor evaded the limit with a made-up header per
        // request. The key is now the kernel's gated client IP; the header (which the kernel only applies for a
        // trusted proxy) plays no part.
        SearchEndpoint ep = endpoint(rateLimiter(1, 1000), engine("search/simple", 0, true));
        for (int i = 0; i < 3; i++) {
            RouteRequest spoofed = new RouteRequest(ACME, Locale.ENGLISH, "GET", "/search/api",
                    Map.of("q", List.of("x")), Map.of("X-Forwarded-For", "10.0.0." + i), new byte[0], Map.of(),
                    "198.51.100.9", "");
            assertEquals(i == 0 ? 200 : 429, ep.handle(spoofed).status(), "query " + i);
        }
    }

    @Test
    void requestsWithoutAClientIpShareOneBucket() {
        SearchEndpoint ep = endpoint(rateLimiter(1, 1000), engine("search/simple", 0, true));
        assertEquals(200, ep.handle(request("GET", "/search/api", "x", null)).status());
        assertEquals(429, ep.handle(request("GET", "/search/api", "x", null)).status(),
                "no client IP never escapes the limit");
    }

    @Test
    void aBlankQueryIsNotCountedAgainstTheLimit() {
        SearchEndpoint ep = endpoint(rateLimiter(1, 1000), engine("search/simple", 0, true));
        assertEquals(200, ep.handle(request("GET", "/search/api", " ")).status());
        assertEquals(200, ep.handle(request("GET", "/search/api", "x")).status());
    }

    // --- reindex (gated POST) ----------------------------------------------------------------------

    @Test
    void reindexFailsClosedWithoutPermission() {
        int[] rebuilt = {0};
        RouteResponse res = endpoint(reindexing("search/simple", 0, 3, rebuilt))
                .handle(request("POST", "/search/reindex", null));
        assertEquals(404, res.status(), "no permission ⇒ 404 (the action is not even revealed)");
        assertEquals(0, rebuilt[0], "and nothing was rebuilt");
    }

    @Test
    void reindexByGetChangesNothingEvenWithPermission() {
        kernel.grant("search|settings");
        int[] rebuilt = {0};
        RouteResponse res = endpoint(reindexing("search/simple", 0, 3, rebuilt))
                .handle(request("GET", "/search/reindex", null));
        assertEquals(404, res.status(), "a link or an <img> cannot trigger it");
        assertEquals(0, rebuilt[0]);
    }

    @Test
    void reindexRebuildsEveryEngineAndReportsTheServingOnesCount() {
        kernel.grant("search|settings");
        kernel.message(null, "search.reindex.done", "Index rebuilt.");
        int[] rebuilt = {0};
        RouteResponse res = endpoint(reindexing("search/simple", 0, 3, rebuilt),
                reindexing("search/lucene", 100, 7, rebuilt)).handle(request("POST", "/search/reindex", null));
        assertEquals(200, res.status());
        assertEquals(2, rebuilt[0], "EVERY registered engine is rebuilt — not only the built-in one");
        assertEquals("Index rebuilt. (7)", body(res),
                "console wording; the count is the serving (highest-priority available) engine's");
    }

    private static SiteSearchEngine reindexing(String key, int priority, int documents, int[] counter) {
        return new SiteSearchEngine() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public List<SearchHit> search(SearchQuery q) {
                return List.of();
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public int reindex(String siteId) {
                counter[0]++;
                return documents;
            }
        };
    }

    // --- install-page (gated POST, through SitePages) ----------------------------------------------

    @Test
    void installPageFailsClosedWithoutPermission() {
        RouteResponse res = endpoint(engine("search/simple", 0, true)).handle(request("POST", "/search/install-page", null));
        assertEquals(404, res.status());
        assertTrue(kernel.installs().isEmpty(), "nothing was installed");
    }

    @Test
    void installPageByGetChangesNothingEvenWithPermission() {
        kernel.grant("search|settings");
        assertEquals(404, endpoint(engine("search/simple", 0, true))
                .handle(request("GET", "/search/install-page", null)).status());
        assertTrue(kernel.installs().isEmpty());
    }

    @Test
    void installPageHandsSitePagesAnEditableNoindexPageCarryingTheWidget() {
        kernel.grant("search|settings");
        kernel.message("acme", "search.page.title", "Find");
        RouteResponse res = endpoint(engine("search/simple", 0, true)).handle(request("POST", "/search/install-page", null));
        assertEquals(200, res.status());
        assertEquals("Search page installed at /search", body(res));
        StarterPage page = kernel.installs().get(0);
        assertEquals("search", page.slug());
        assertEquals("/search", page.path());
        assertTrue(page.noindex(), "the results page installs noindex — the canonical index opt-out for search results");
        assertEquals(Map.of("en", "Find"), page.titles(), "titled in the site's default language, from Messages.site");
        assertTrue(page.html().contains("<div id=\"search-box\" data-widget=\"search/box\"></div>"),
                "the page carries the search-box widget anchor (id and data-widget on one element)");
        assertTrue(page.html().contains("<title>Find</title>"));
        assertTrue(page.logic().contains("~{${master} :: head}"), "the logic pulls the master chrome");
    }

    @Test
    void installPageEscapesTheTitleInTheMarkup() {
        kernel.grant("search|settings");
        kernel.message("acme", "search.page.title", "<b>Find</b>");
        endpoint(engine("search/simple", 0, true)).handle(request("POST", "/search/install-page", null));
        assertTrue(kernel.installs().get(0).html().contains("&lt;b&gt;Find&lt;/b&gt;"));
    }

    @Test
    void installPageReportsEveryOutcomeInTheOperatorsWords() {
        kernel.grant("search|settings");
        kernel.nextInstall(InstallResult.Outcome.PENDING_REVIEW);
        assertEquals("The search page was created and is waiting in the review queue: /search", installAt("a"));

        kernel.nextInstall(InstallResult.Outcome.DRAFT_BLOCKED,
                new Finding(Severity.ERROR, "seo.title", "Title missing"));
        assertEquals("The search page was created as a draft because the quality checks blocked publishing; "
                + "fix it in the page editor: /search\n- seo.title: Title missing", installAt("b"));

        kernel.page("c", "search", "/buscar");
        assertEquals("A search page already exists at /buscar", installAt("c"),
                "an existing page is reported where it lives now, never overwritten");
    }

    private String installAt(String siteId) {
        SiteView site = new SiteView(siteId, siteId, Locale.ENGLISH, List.of(Locale.ENGLISH), null);
        kernel.site(site);
        RouteRequest req = new RouteRequest(site, Locale.ENGLISH, "POST", "/search/install-page", Map.of(), Map.of(),
                new byte[0]);
        RouteResponse res = endpoint(engine("search/simple", 0, true)).handle(req);
        assertEquals(200, res.status());
        return body(res);
    }

    @Test
    void aFailedInstallIsA500WithoutDetail() {
        kernel.grant("search|settings");
        SearchEndpoint failing = new SearchEndpoint(List.of(engine("search/simple", 0, true)), settings, kernel,
                Fixtures.unlimited(), kernel, new ThrowingPages(),
                new NoResultsLog(new Fixtures.NoFiles(), settings, Clock.systemUTC(), false));
        RouteResponse res = failing.handle(request("POST", "/search/install-page", null));
        assertEquals(500, res.status());
        assertEquals("Could not install the search page.", body(res), "no internal detail reaches the operator");
    }

    /** {@code SitePages} whose install fails the way the kernel's does for a taken path or a failed write. */
    private static final class ThrowingPages implements SitePages {
        @Override
        public Optional<String> pathOf(String siteId, String slug) {
            return Optional.empty();
        }

        @Override
        public InstallResult install(String siteId, StarterPage page) {
            throw new IllegalStateException("another page serves /search");
        }
    }
}
