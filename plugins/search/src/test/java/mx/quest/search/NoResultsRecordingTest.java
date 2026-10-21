package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static mx.quest.search.Fixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SiteSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where no-result searches get recorded and cleared: the box widget and the JSON API record a query that found
 * nothing (and only that), the gated {@code POST /search/no-results/clear} empties the list and redirects back to
 * the settings page, the admin model exposes the list, and a wholesale site event makes the list reload from disk.
 */
class NoResultsRecordingTest {

    @TempDir
    Path root;

    private final Map<String, Object> cfg = new HashMap<>(Map.of("enabled", true, "recordNoResults", true));
    private FakePluginSettings settings;
    private FakeSiteServices kernel;
    private NoResultsLog noResults;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("acme"));
        settings = new FakePluginSettings();
        refreshSettings();
        kernel = new FakeSiteServices().site(ACME);
        noResults = new NoResultsLog(new TempDirSiteFiles(root, "search"), settings, Clock.systemUTC(), false);
    }

    private void refreshSettings() {
        settings.site("acme", cfg);
    }

    private SearchEndpoint endpoint(SiteSearchEngine engine) {
        return new SearchEndpoint(List.of(engine), settings, kernel, Fixtures.unlimited(), kernel, kernel, noResults);
    }

    private static SiteSearchEngine engine(SearchHit... hits) {
        return Fixtures.engine("search/simple", 0, true, hits);
    }

    private void renderWidget(SiteSearchEngine engine, String q) {
        SearchBoxWidget widget = new SearchBoxWidget(List.of(engine), settings, kernel, noResults);
        PageMeta page = new PageMeta("search", "Search", "", List.of(Locale.ENGLISH), Locale.ENGLISH, false, false);
        widget.render(new WidgetView(ACME, page, Locale.ENGLISH, "search-box", false, Map.of(),
                q == null ? Map.of() : Map.of("q", q)));
    }

    private List<String> terms() {
        return noResults.list("acme").stream().map(NoResultsLog.Entry::term).toList();
    }

    @Test
    void theWidgetRecordsAQueryThatFoundNothing() {
        renderWidget(engine(), "Gift Cards");
        assertEquals(List.of("gift cards"), terms());
    }

    @Test
    void theWidgetDoesNotRecordAQueryWithResultsOrABareRender() {
        renderWidget(engine(new SearchHit("/g", "Guide", "", 1)), "guide");
        renderWidget(engine(), null);
        renderWidget(engine(), "   ");
        assertTrue(terms().isEmpty());
    }

    @Test
    void theWidgetRecordsNothingWhenSearchIsDisabled() {
        cfg.put("enabled", false);
        refreshSettings();
        renderWidget(engine(), "anything");
        assertTrue(terms().isEmpty());
    }

    @Test
    void theJsonApiRecordsAQueryThatFoundNothingAndOnlyThat() {
        endpoint(engine()).handle(request("GET", "/search/api", "warranty"));
        endpoint(engine(new SearchHit("/a", "A", "", 1))).handle(request("GET", "/search/api", "found"));
        endpoint(engine()).handle(request("GET", "/search/api", null));
        assertEquals(List.of("warranty"), terms());
    }

    @Test
    void theClearActionIsANonAnonymousPostRoute() {
        Route clear = endpoint(engine()).routes().stream()
                .filter(r -> r.pathPattern().equals(SearchEndpoint.CLEAR_NO_RESULTS_PATH)).findFirst().orElseThrow();
        assertEquals("POST", clear.method());
        assertFalse(clear.anonymous(), "the kernel's CSRF check must apply");
        assertFalse(clear.posture().indexable());
    }

    @Test
    void clearWithThePermissionEmptiesTheListAndRedirectsToSettings() {
        endpoint(engine()).handle(request("GET", "/search/api", "returns"));
        noResults.flush();
        assertEquals(1, terms().size());
        assertTrue(Files.exists(root.resolve("acme/plugins/search/" + NoResultsLog.FILE)));
        kernel.grant("search|settings");

        RouteResponse response = endpoint(engine()).handle(request("POST", SearchEndpoint.CLEAR_NO_RESULTS_PATH, null));

        assertEquals(303, response.status());
        assertEquals("/__admin/plugins/search/settings", response.headers().get("Location"));
        assertTrue(terms().isEmpty());
        assertFalse(Files.exists(root.resolve("acme/plugins/search/" + NoResultsLog.FILE)));
    }

    @Test
    void clearWithoutThePermissionFailsClosedAndKeepsTheList() {
        endpoint(engine()).handle(request("GET", "/search/api", "returns"));

        RouteResponse response = endpoint(engine()).handle(request("POST", SearchEndpoint.CLEAR_NO_RESULTS_PATH, null));

        assertEquals(404, response.status());
        assertEquals(List.of("returns"), terms());
    }

    @Test
    void clearByGetIsRefused() {
        endpoint(engine()).handle(request("GET", "/search/api", "returns"));
        kernel.grant("search|settings");
        assertEquals(404, endpoint(engine()).handle(request("GET", SearchEndpoint.CLEAR_NO_RESULTS_PATH, null))
                .status());
        assertEquals(List.of("returns"), terms());
    }

    @Test
    void theAdminModelExposesTheListAndTheSetting() {
        endpoint(engine()).handle(request("GET", "/search/api", "Shipping"));
        SiteSearchCorpus corpus = new SiteSearchCorpus(new FakeContentCatalog().site("acme"), settings);
        SearchAdminModel model = new SearchAdminModel(List.of(engine()), corpus, kernel, settings, noResults);
        assertTrue(model.noResultsRecording("acme"));
        assertEquals(List.of("shipping"), model.noResults("acme").stream().map(NoResultsLog.Entry::term).toList());
        assertEquals(NoResultsLog.DEFAULT_RETENTION_DAYS, model.noResultsRetentionDays("acme"));
        assertEquals(NoResultsLog.MAX_TERMS, model.noResultsMaxTerms());
        assertTrue(model.lastSeen(model.noResults("acme").get(0)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));

        cfg.put("recordNoResults", false);
        refreshSettings();
        assertFalse(model.noResultsRecording("acme"));
        assertFalse(model.noResultsRecording(null), "no bound site (the application console): nothing");
        assertTrue(model.noResults(null).isEmpty());
    }

    @Test
    void aWholesaleSiteEventReloadsTheListFromTheSiteFolderAndOthersKeepIt() throws IOException {
        SimpleSearchEngine simple = new SimpleSearchEngine(
                new SiteSearchCorpus(new FakeContentCatalog().site("acme"), settings), kernel, settings,
                new SearchProperties());
        EventDispatch events = new EventDispatch("search", Set.of())
                .listener(new SearchSiteEvents(simple, noResults));
        endpoint(engine()).handle(request("GET", "/search/api", "in-memory"));
        Path file = root.resolve("acme/plugins/search/" + NoResultsLog.FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "from-backup\t3\t" + System.currentTimeMillis() + "\n");

        events.lifecycle("acme", SiteLifecycleEvent.Kind.SUSPENDED, false);
        assertEquals(List.of("in-memory"), terms(), "a metadata-only lifecycle change keeps the in-memory list");
        events.lifecycle("acme", SiteLifecycleEvent.Kind.DELETED, false);
        assertEquals(List.of("from-backup"), terms(), "a wholesale event drops the unwritten memory; the file is read");
        endpoint(engine()).handle(request("GET", "/search/api", "after"));
        events.lifecycle("acme", SiteLifecycleEvent.Kind.RESTORED, true);
        assertEquals(List.of("from-backup"), terms(), "a restore reloads the restored file, unwritten memory dropped");
        assertTrue(events.failures().isEmpty(), "no callback failed: " + events.failures());
    }
}
