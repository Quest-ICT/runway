package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.SiteEventListener;
import org.junit.jupiter.api.Test;

/** The records and contracts SPI 0.6.0 adds: frozen, null-normalized, and their small helpers. */
class K3RecordsTest {

    private static final Locale ES = Locale.forLanguageTag("es");

    private static ContentRef ref(String id) {
        return ContentRef.of(id, "/" + id, Map.of(), List.of(), Map.of(), Map.of(), "__plugin__/x/t", null);
    }

    @Test
    void theSpiIsAtOneZero() {
        assertEquals("1.0.0", RunwaySpi.VERSION);
    }

    // --- LocalizedUrl ----------------------------------------------------------------------------------------

    @Test
    void aLocalizedUrlWithoutAnOverrideIsItsOwnCanonical() {
        LocalizedUrl url = new LocalizedUrl(Locale.ENGLISH, "/about", null);
        assertEquals("/about", url.canonical());
        assertEquals("/about", new LocalizedUrl(Locale.ENGLISH, "/about", "  ").canonical());
        assertEquals("https://elsewhere.test/x", new LocalizedUrl(Locale.ENGLISH, "/about",
                "https://elsewhere.test/x").canonical(), "an override is kept as given");
    }

    @Test
    void absoluteAndAlternateUseTheRealPathNeverTheCanonical() {
        LocalizedUrl url = new LocalizedUrl(ES, "/es/acerca", "https://elsewhere.test/x");
        assertEquals("https://acme.test/es/acerca", url.absolute("https://acme.test"));
        assertEquals("https://acme.test/es/acerca", url.absolute("https://acme.test/"), "a trailing slash is dropped");
        assertEquals("/es/acerca", url.absolute(""));
        Alternate alternate = url.alternate("https://acme.test");
        assertEquals(ES, alternate.locale());
        assertEquals("https://acme.test/es/acerca", alternate.url());
    }

    @Test
    void aLocalizedUrlNeedsALocaleAndAPath() {
        assertThrows(NullPointerException.class, () -> new LocalizedUrl(null, "/x", null));
        assertThrows(NullPointerException.class, () -> new LocalizedUrl(Locale.ENGLISH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedUrl(Locale.ENGLISH, " ", null));
        assertThrows(NullPointerException.class, () -> new LocalizedUrl(Locale.ENGLISH, "/x", null).absolute(null));
    }

    // --- CatalogEntry ----------------------------------------------------------------------------------------

    @Test
    void anEntryIsFrozenAndNamesItselfByComposite() {
        List<LocalizedUrl> urls = new ArrayList<>(List.of(new LocalizedUrl(Locale.ENGLISH, "/hello", null)));
        CatalogEntry entry = new CatalogEntry("blog", ref("hello"), urls, "/hello");
        urls.clear();
        assertEquals(1, entry.urls().size(), "the list is copied");
        assertThrows(UnsupportedOperationException.class, () -> entry.urls().add(null));
        assertEquals("blog:hello", entry.compositeId());
        assertFalse(entry.isPage());
        assertTrue(new CatalogEntry(ContentCatalogView.PAGES_SOURCE, ref("about"), List.of(), null).isPage());
    }

    @Test
    void pathForFallsBackToThePrimaryLocalesRealAddress() {
        // An item that exists only in Spanish on an English-default site (review n6): an English context links its
        // Spanish URL — never the unprefixed English path, which 404s.
        CatalogEntry entry = new CatalogEntry("blog", ref("hola"),
                List.of(new LocalizedUrl(ES, "/es/hola", null)), "/es/hola");
        assertEquals("/es/hola", entry.pathFor(ES));
        assertEquals("/es/hola", entry.pathFor(Locale.ENGLISH));
    }

    @Test
    void aMissingXDefaultFallsBackToTheFirstUrlThenTheRefPath() {
        assertEquals("/es/hola", new CatalogEntry("blog", ref("hola"),
                List.of(new LocalizedUrl(ES, "/es/hola", null)), null).xDefaultPath());
        assertEquals("/hola", new CatalogEntry("blog", ref("hola"), null, "").xDefaultPath());
        assertEquals(List.of(), new CatalogEntry("blog", ref("hola"), null, null).urls());
    }

    // --- events ----------------------------------------------------------------------------------------------

    @Test
    void lifecycleWholesaleIsDeletedOrRestoredOnly() {
        for (SiteLifecycleEvent.Kind kind : SiteLifecycleEvent.Kind.values()) {
            boolean expected = kind == SiteLifecycleEvent.Kind.DELETED || kind == SiteLifecycleEvent.Kind.RESTORED;
            assertEquals(expected, new SiteLifecycleEvent("acme", kind, false, true).wholesale(), kind.name());
        }
        assertThrows(NullPointerException.class, () -> new SiteLifecycleEvent(null, SiteLifecycleEvent.Kind.CREATED,
                false, true));
        assertThrows(NullPointerException.class, () -> new SiteLifecycleEvent("acme", null, false, true));
    }

    @Test
    void aCatalogChangeNormalizesItsUrlLists() {
        List<LocalizedUrl> previous = new ArrayList<>(List.of(new LocalizedUrl(Locale.ENGLISH, "/old", null)));
        CatalogChange change = new CatalogChange("acme", CatalogChange.Cause.PAGE, "pages", "about", null, previous,
                null, true);
        previous.clear();
        assertEquals(1, change.previousUrls().size());
        assertEquals(List.of(), change.currentUrls());
        assertThrows(UnsupportedOperationException.class, () -> change.previousUrls().clear());
        assertThrows(NullPointerException.class, () -> new CatalogChange(null, CatalogChange.Cause.SITE, null, null,
                null, null, null, true));
        assertThrows(NullPointerException.class, () -> new CatalogChange("acme", null, null, null, null, null, null,
                true));
    }

    @Test
    void aSettingsChangeNamesSiteAndPlugin() {
        assertEquals("contact", new SettingsChange("acme", "contact", false).pluginId());
        assertThrows(NullPointerException.class, () -> new SettingsChange("acme", null, true));
    }

    // --- listener and screen contracts -----------------------------------------------------------------------

    @Test
    void aListenerDefaultsToTheConsumerPhaseAndIgnoresEverything() {
        SiteEventListener listener = new SiteEventListener() { };
        assertEquals(SiteEventListener.Phase.CONSUMER, listener.phase());
        listener.onSiteLifecycle(new SiteLifecycleEvent("acme", SiteLifecycleEvent.Kind.UPDATED, false, true));
        listener.onSettingsChanged(new SettingsChange("acme", "x", true));
        listener.onCatalogChanged(new CatalogChange("acme", CatalogChange.Cause.SITE, null, null, null, null, null,
                true));
    }

    @Test
    void theConsoleScreenAnnotationIsVisibleAtRuntimeAndNamesTheShellContract() {
        Retention retention = ConsoleScreen.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value(), "the host's advice selects by it at runtime");
        assertEquals("operator/op-layout", ConsoleScreen.LAYOUT_VIEW);
        assertEquals("contentTemplate", ConsoleScreen.CONTENT_TEMPLATE);
        assertEquals("pageTitleKey", ConsoleScreen.PAGE_TITLE_KEY);
    }
}
