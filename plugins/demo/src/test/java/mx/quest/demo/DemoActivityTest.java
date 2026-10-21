package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CONSUMER listener, driven by the testkit's {@link EventDispatch} in the kernel's order. */
class DemoActivityTest {

    @TempDir
    Path sites;
    private DemoNotesStore notes;
    private DemoActivity activity;
    private EventDispatch events;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        notes = new DemoNotesStore(new TempDirSiteFiles(sites, "runway-demo"));
        activity = new DemoActivity(notes, new FakeSiteServices()
                .site(new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null)));
        events = new EventDispatch("runway-demo", Set.of("runway-demo")).listener(activity);
    }

    private static LocalizedUrl url(String path) {
        return new LocalizedUrl(Locale.ENGLISH, path, path);
    }

    @Test
    void itIsAConsumer() {
        assertEquals(SiteEventListener.Phase.CONSUMER, activity.phase());
    }

    @Test
    void aSaveOfItsOwnSettingsAppendsOneAuditNote() {
        int before = notes.count("acme");
        events.settings("acme", "runway-demo");
        List<Note> after = notes.list("acme");
        assertEquals(before + 1, after.size());
        assertTrue(after.getLast().text().startsWith("Per-site settings saved at "), after.getLast().text());
        assertEquals(Note.ORIGIN_SYSTEM, after.getLast().origin());
        assertTrue(events.failures().isEmpty());
    }

    @Test
    void anotherPluginsSettingsSaveWritesNoNoteButIsRecordedAsACatalogChange() {
        int before = notes.count("acme");
        events.settings("acme", "contact");
        assertEquals(before, notes.count("acme"), "onSettingsChanged is for this plugin's own settings only");
        DemoActivity.Entry entry = activity.recent("acme").getFirst();
        assertEquals("settings", entry.cause());
        assertEquals("contact", entry.item());
    }

    @Test
    void aChangeReplayedFromAnotherNodeWritesNoSecondNote() {
        int before = notes.count("acme");
        activity.onSettingsChanged(new SettingsChange("acme", "runway-demo", false));
        assertEquals(before, notes.count("acme"), "the node where the save happened wrote the note");
    }

    @Test
    void aPageChangeIsRecordedWithItsUrlsBeforeAndAfter() {
        events.page("acme", "about", List.of(url("/about")), List.of(url("/about-us")));
        DemoActivity.Entry entry = activity.recent("acme").getFirst();
        assertEquals("page", entry.cause());
        assertEquals("pages:about", entry.item());
        assertEquals(List.of("/about"), entry.previousUrls());
        assertEquals(List.of("/about-us"), entry.currentUrls());
        assertTrue(entry.local());
    }

    @Test
    void aDeletionStillNamesTheOldAddressAndAWholeSourceChangeShowsAStar() {
        events.page("acme", "gone", List.of(url("/gone")), List.of());
        events.catalog(new CatalogChange("acme", CatalogChange.Cause.CONTENT, "blog", null, "blog", null, null,
                false));
        List<DemoActivity.Entry> recent = activity.recent("acme");
        assertEquals("blog:*", recent.get(0).item(), "newest first; a null id is the whole corpus");
        assertFalse(recent.get(0).local());
        assertEquals(List.of("/gone"), recent.get(1).previousUrls());
        assertEquals(List.of(), recent.get(1).currentUrls());
    }

    @Test
    void onlyTheNewestChangesAreKept() {
        for (int i = 0; i < DemoActivity.KEEP + 4; i++) {
            events.page("acme", "p" + i, List.of(), List.of());
        }
        List<DemoActivity.Entry> recent = activity.recent("acme");
        assertEquals(DemoActivity.KEEP, recent.size());
        assertEquals("pages:p" + (DemoActivity.KEEP + 3), recent.getFirst().item());
    }

    @Test
    void aWholesaleEventDropsWhatWasRecordedForTheSite() {
        events.page("acme", "about", List.of(), List.of());
        events.lifecycle("acme", SiteLifecycleEvent.Kind.DELETED, false);
        List<DemoActivity.Entry> recent = activity.recent("acme");
        assertEquals(1, recent.size(), "only the deletion's own SITE change remains (lifecycle runs first)");
        assertEquals("site", recent.getFirst().cause());
        events.lifecycle("acme", SiteLifecycleEvent.Kind.SUSPENDED, false);
        assertEquals(2, activity.recent("acme").size(), "a non-wholesale event keeps the record");
        assertTrue(events.failures().isEmpty(), "an unknown site in Sites is handled: " + events.failures());
    }

    @Test
    void anUnknownSiteHasNoActivityAndANoteFailureNeverPropagates() {
        assertEquals(List.of(), activity.recent("nobody"));
        activity.onSettingsChanged(new SettingsChange("ghost", "runway-demo", true));   // no folder: logged only
        events.lifecycle("ghost", SiteLifecycleEvent.Kind.CREATED, false);             // unknown to Sites
        assertTrue(events.failures().isEmpty(), events.failures().toString());
    }
}
