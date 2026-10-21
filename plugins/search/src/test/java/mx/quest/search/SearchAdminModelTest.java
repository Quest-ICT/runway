package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static mx.quest.search.Fixtures.engine;
import static mx.quest.search.Fixtures.entry;
import static mx.quest.search.Fixtures.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.Test;

/**
 * The console status model: the searchable-items count is the corpus (published items minus exclusions), the
 * installed-page status and path come from {@code SitePages} (any state, following a move), the active engine, and
 * a neutral answer for a {@code null} site (the application console has none).
 */
class SearchAdminModelTest {

    private final FakePluginSettings settings = new FakePluginSettings();
    private final FakeSiteServices kernel = new FakeSiteServices().site(ACME);
    private final FakeContentCatalog catalog = new FakeContentCatalog()
            .entry("acme", page("home", "Home", "")).entry("acme", page("privacy", "Privacy", ""))
            .entry("acme", entry("blog", "first", "First", ""));

    private SearchAdminModel model() {
        return new SearchAdminModel(List.of(engine("search/simple", 0, true), engine("search/lucene", 10, false)),
                new SiteSearchCorpus(catalog, settings), kernel, settings,
                new NoResultsLog(new Fixtures.NoFiles(), settings, Clock.systemUTC(), false));
    }

    @Test
    void searchableItemsCountsTheCorpusPagesAndSourceItemsMinusExclusions() {
        assertEquals(3, model().searchableItems("acme"), "pages and content-source items alike");
        settings.site("acme", Map.of("exclude", List.of("privacy")));
        assertEquals(2, model().searchableItems("acme"), "what the index holds, so excluded items do not count");
        assertEquals(0, model().searchableItems(null));
    }

    @Test
    void theInstalledPageIsFoundBySlugWhereverItLives() {
        assertFalse(model().pageInstalled("acme"));
        assertEquals("", model().pagePath("acme"));
        kernel.page("acme", "search", "/buscar");
        assertTrue(model().pageInstalled("acme"));
        assertEquals("/buscar", model().pagePath("acme"), "the live path, after the operator moved it");
        assertFalse(model().pageInstalled(null));
        assertEquals("", model().pagePath(null));
    }

    @Test
    void theActiveEngineIsTheHighestPriorityAvailableOne() {
        assertEquals("search/simple", model().activeEngineKey("acme"), "lucene is installed but not available");
        assertEquals(2, model().engineCount());
        assertEquals("none", model().activeEngineKey(null));
    }

    @Test
    void enabledFollowsTheSetting() {
        assertTrue(model().enabled("acme"), "default on");
        settings.site("acme", Map.of("enabled", false));
        assertFalse(model().enabled("acme"));
        assertTrue(model().enabled(null));
    }
}
