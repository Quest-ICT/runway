package mx.quest.runway.spi.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.service.SiteEventListener;
import org.junit.jupiter.api.Test;

/** The SPI 0.6.0 fakes keep the kernel's rules: input checks, path rules, phase order, isolation, refusals. */
class K3FakesTest {

    private static CatalogEntry entry(String id) {
        ContentRef ref = ContentRef.of(id, "/blog/" + id, Map.of(), List.of(), Map.of(), Map.of(), "__plugin__/b/t",
                null);
        return new CatalogEntry("blog", ref, List.of(new LocalizedUrl(Locale.ENGLISH, "/blog/" + id, null)), null);
    }

    @Test
    void theCatalogFakeAnswersWhatTheTestDeclared() {
        FakeContentCatalog catalog = new FakeContentCatalog().entry("acme", entry("hello"))
                .page("acme", "/about", Locale.ENGLISH, "about").text("acme", "blog:hello", "<p>Hi</p>")
                .route("/sitemap.xml");
        assertEquals(List.of("blog:hello"), catalog.entries("acme").stream().map(CatalogEntry::compositeId).toList());
        assertEquals("<p>Hi</p>", catalog.indexableText("acme", entry("hello"), Locale.ENGLISH));
        assertEquals("", catalog.indexableText("acme", entry("other"), Locale.ENGLISH));
        assertEquals(Optional.of("about"), catalog.pageAt("acme", "/about", Locale.ENGLISH));
        assertEquals(Optional.empty(), catalog.pageAt("acme", "/about", Locale.forLanguageTag("es")));
        assertEquals(List.of(), catalog.entries("unknown"));
        catalog.refresh("acme");
        assertEquals(List.of("acme"), catalog.refreshed());
    }

    @Test
    void theCatalogFakeAppliesTheKernelsPathAndIdRules() {
        FakeContentCatalog catalog = new FakeContentCatalog().site("acme").route("/sitemap.xml");
        assertTrue(catalog.servablePath("acme", "/blog"));
        assertTrue(catalog.servablePath("acme", "/blog/"));
        for (String bad : new String[] {"/login", "/logout", "/error", "/reset", "/forgot-password", "/__admin",
                "/actuator/health", "/app/x", "/static/x", "/a/../b", "//x", "x", "/a|b", "/sitemap.xml"}) {
            assertFalse(catalog.servablePath("acme", bad), bad);
        }
        assertFalse(catalog.servablePath("unknown", "/blog"));
        assertThrows(IllegalArgumentException.class, () -> catalog.entries("../x"));
        assertThrows(IllegalArgumentException.class, () -> catalog.servablePath(null, "/x"));
        assertThrows(IllegalArgumentException.class, () -> catalog.refresh("a/b"));
    }

    private static final class Probe implements SiteEventListener {
        final String name;
        final Phase phase;
        final List<String> log;

        Probe(String name, Phase phase, List<String> log) {
            this.name = name;
            this.phase = phase;
            this.log = log;
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public void onSiteLifecycle(SiteLifecycleEvent event) {
            log.add(name + ":lifecycle");
        }

        @Override
        public void onSettingsChanged(SettingsChange change) {
            log.add(name + ":settings");
        }

        @Override
        public void onCatalogChanged(CatalogChange change) {
            log.add(name + ":catalog:" + change.currentUrls().size());
        }
    }

    @Test
    void theDispatchFakeAppliesTheKernelsOrder() {
        List<String> log = new ArrayList<>();
        EventDispatch events = new EventDispatch("blog", Set.of("blog"))
                .listener(new Probe("consumer", SiteEventListener.Phase.CONSUMER, log))
                .listener(new Probe("source", SiteEventListener.Phase.SOURCE, log));
        events.lifecycle("acme", SiteLifecycleEvent.Kind.RESTORED, false);
        events.settings("acme", "blog");
        events.settings("acme", "other");
        LocalizedUrl url = new LocalizedUrl(Locale.ENGLISH, "/x", null);
        events.page("acme", "x", List.of(), List.of(url));
        assertEquals(List.of(
                "source:lifecycle", "source:catalog:0", "consumer:lifecycle", "consumer:catalog:0",
                "source:settings", "source:catalog:0", "consumer:settings", "consumer:catalog:0",
                "source:catalog:0", "consumer:catalog:0",
                "source:catalog:0", "consumer:catalog:1"), log,
                "phases, the per-listener order, own-plugin settings only, no current URLs in the source phase");
    }

    @Test
    void theDispatchFakeIsolatesAndRefusesLikeTheKernel() {
        List<String> log = new ArrayList<>();
        EventDispatch[] self = new EventDispatch[1];
        List<Throwable> refusals = new ArrayList<>();
        self[0] = new EventDispatch("blog", Set.of("blog"))
                .listener(new SiteEventListener() {
                    @Override
                    public void onCatalogChanged(CatalogChange change) {
                        try {
                            self[0].changed("acme", "blog", "y");
                        } catch (IllegalStateException e) {
                            refusals.add(e);
                        }
                        throw new IllegalStateException("boom");
                    }
                })
                .listener(new Probe("after", SiteEventListener.Phase.CONSUMER, log));
        self[0].changed("acme", "blog", "x");
        assertEquals(1, refusals.size(), "ContentChanges is refused from a callback");
        assertEquals(1, self[0].failures().size());
        assertEquals(List.of("after:catalog:0"), log, "the next listener still ran");
        assertEquals("blog", self[0].announced().get(0).pluginId());
        assertThrows(IllegalArgumentException.class, () -> self[0].changed("acme", "pages", "x"));
        assertThrows(IllegalArgumentException.class, () -> self[0].changed("../x", "blog", "x"));
    }
}
