package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

class SiteLifecycleServiceTest {

    private static final Locale EN = Locale.ENGLISH;

    private record Fixture(SiteStore store, StorageLayout layout, List<Object> events, SiteLifecycleService service) {
    }

    private static Fixture fixture(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        SiteStore store = new SiteStore(layout, new YamlStore());
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        return new Fixture(store, layout, events, new SiteLifecycleService(store, layout, publisher, new mx.quest.runway.i18n.SiteMessagesStore(layout)));
    }

    private static List<SiteChangedEvent.Kind> kinds(List<Object> events) {
        return events.stream().map(SiteChangedEvent.class::cast).map(SiteChangedEvent::kind).toList();
    }

    @Test
    void createProvisionsAndPublishes(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        Site site = f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        assertTrue(site.active());
        assertTrue(f.store.exists("acme"));
        assertTrue(Files.isRegularFile(f.layout.siteDir("acme").resolve("site.yml")));
        assertEquals(List.of(SiteChangedEvent.Kind.CREATED), kinds(f.events));
        // The default (no-template) create carries no template id on the event.
        assertNull(((SiteChangedEvent) f.events.get(0)).templateId());
    }

    @Test
    void createCarriesTheChosenTemplateOnTheEvent(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "corporate");
        SiteChangedEvent event = (SiteChangedEvent) f.events.get(0);
        assertEquals(SiteChangedEvent.Kind.CREATED, event.kind());
        assertEquals("corporate", event.templateId(), "the seeder learns the chosen template from the event");
    }

    @Test
    void changingTheDefaultLocaleMigratesMessageBundlesSoTheEditorStaysCorrect(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        Locale es = Locale.of("es");
        Locale fr = Locale.of("fr");
        f.service.create("nb", "Northbeam", List.of("nb.test"), EN, List.of(EN, es, fr));

        // Seed messages as the editor would with an en default: English in the base file, es/fr suffixed.
        var messages = new mx.quest.runway.i18n.SiteMessagesStore(f.layout);
        messages.save("nb", List.of(EN, es, fr),
                Map.of("nav.about", Map.of("en", "About", "es", "Acerca", "fr", "À propos")));

        // Switch the site default en → es through the real config path.
        f.service.updateConfig("nb", "Northbeam", es, List.of(es, EN, fr));

        // The Messages editor, loaded default-first, now shows each language under ITS OWN column (the bug had
        // English under Spanish and a blank English column).
        var grid = messages.load("nb", List.of(es, EN, fr));
        assertEquals("Acerca", grid.get("nav.about").get("es"));
        assertEquals("About", grid.get("nav.about").get("en"));
        assertEquals("À propos", grid.get("nav.about").get("fr"));
    }

    @Test
    void createRejectsDuplicate(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        assertThrows(IllegalStateException.class,
                () -> f.service.create("acme", "Acme2", List.of("acme2.test"), EN, List.of(EN)));
    }

    @Test
    void suspendThenResume(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        assertTrue(f.service.suspend("acme").suspended());
        assertEquals(SiteStatus.SUSPENDED, f.store.find("acme").orElseThrow().status());
        assertTrue(f.service.resume("acme").active());
        assertEquals(SiteStatus.ACTIVE, f.store.find("acme").orElseThrow().status());
        assertEquals(List.of(SiteChangedEvent.Kind.CREATED, SiteChangedEvent.Kind.SUSPENDED, SiteChangedEvent.Kind.RESUMED),
                kinds(f.events));
    }

    @Test
    void transitionUnknownSiteThrows(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> fixture(tmp).service.suspend("ghost"));
    }

    @Test
    void deleteRemovesTheWholeFolder(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        Files.writeString(f.layout.siteDir("acme").resolve("extra.txt"), "plugin data");

        assertTrue(f.service.delete("acme"));
        assertFalse(Files.exists(f.layout.siteDir("acme")), "the entire site folder is removed");
        assertTrue(f.store.find("acme").isEmpty());
        assertTrue(kinds(f.events).contains(SiteChangedEvent.Kind.DELETED));
    }

    @Test
    void deleteAbsentSiteIsFalse(@TempDir Path tmp) {
        assertFalse(fixture(tmp).service.delete("ghost"));
    }

    @Test
    void seedsDefaultSiteOnlyWhenEmpty(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        Site seeded = f.service.seedDefaultSite().orElseThrow();
        assertEquals("default", seeded.id());
        assertTrue(f.store.exists("default"));
        assertTrue(f.service.seedDefaultSite().isEmpty(), "does not re-seed when sites already exist");
    }

    @Test
    void updateHostsNormalizesDeduplicatesAndPublishes(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        f.events.clear();
        Site updated = f.service.updateHosts("acme", List.of("  WWW.Acme.COM ", "acme.test", "www.acme.com", ""));
        // lowercased + trimmed + blanks dropped + de-duplicated, first-seen order preserved
        assertEquals(List.of("www.acme.com", "acme.test"), updated.hosts());
        assertEquals(List.of(SiteChangedEvent.Kind.UPDATED), kinds(f.events));
        assertEquals(List.of("www.acme.com", "acme.test"), f.store.find("acme").orElseThrow().hosts(), "persisted");
    }

    @Test
    void updateHostsAllowsEmpty(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        assertTrue(f.service.updateHosts("acme", List.of("  ", "")).hosts().isEmpty(), "empty ⇒ catch-all only");
    }

    @Test
    void updateHostsRejectsMalformedHosts(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("acme.test"), EN, List.of(EN));
        for (String bad : List.of("has space.com", "under_score.com", "-lead.com", "http://acme.test",
                "acme.test:8080", "acme..test", "trail-.com")) {
            assertThrows(IllegalArgumentException.class,
                    () -> f.service.updateHosts("acme", List.of(bad)), () -> "should reject: " + bad);
        }
        assertEquals(List.of("acme.test"), f.store.find("acme").orElseThrow().hosts(), "unchanged after rejects");
    }

    @Test
    void updateHostsRejectsHostOwnedByAnotherSite(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("shared.test"), EN, List.of(EN));
        f.service.create("other", "Other", List.of("other.test"), EN, List.of(EN));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.service.updateHosts("other", List.of("SHARED.test")), "case-insensitive cross-site clash");
        assertTrue(ex.getMessage().contains("acme"), ex.getMessage());
        // a site may keep / re-list its OWN host (no self-conflict)
        assertEquals(List.of("shared.test", "acme2.test"),
                f.service.updateHosts("acme", List.of("shared.test", "acme2.test")).hosts());
    }

    @Test
    void createRejectsAHostAnotherSiteAnswersOnAndLeavesNothingBehind(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        f.service.create("acme", "Acme", List.of("shared.test"), EN, List.of(EN));
        f.events.clear();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.service.create("other", "Other", List.of("x.test", " Shared.TEST "), EN, List.of(EN)));
        assertTrue(ex.getMessage().contains("acme"), ex.getMessage());
        assertFalse(f.store.exists("other"), "no half-created site");
        assertTrue(f.events.isEmpty(), "nothing published");
    }

    @Test
    void createNormalizesAndRejectsMalformedHosts(@TempDir Path tmp) {
        Fixture f = fixture(tmp);
        assertEquals(List.of("acme.test", "www.acme.test"),
                f.service.create("acme", "Acme", List.of(" ACME.test", "www.acme.test", "acme.test", ""), EN,
                        List.of(EN)).hosts(), "lowercased, trimmed, de-duplicated, blanks dropped");
        for (String bad : List.of("acme.test:8080", "http://b.test", "*.b.test", "b test")) {
            assertThrows(IllegalArgumentException.class,
                    () -> f.service.create("b", "B", List.of(bad), EN, List.of(EN)), () -> "should reject: " + bad);
        }
        assertFalse(f.store.exists("b"));
    }

    @Test
    void concurrentCreatesNeverBothClaimOneHost(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        int n = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = "s" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        f.service.create(id, id, List.of("race.test"), EN, List.of(EN));
                        return true;
                    } catch (IllegalArgumentException taken) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int created = 0;
            for (var r : results) {
                created += r.get() ? 1 : 0;
            }
            assertEquals(1, created, "exactly one site gets the host");
            assertEquals(1, f.store.findAll().stream().filter(s -> s.hosts().contains("race.test")).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void updateHostsUnknownSiteThrows(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> fixture(tmp).service.updateHosts("ghost", List.of("x.test")));
    }
}
