package mx.quest.runway.i18n;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.NoSuchMessageException;

class SiteAwareMessageSourceTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private record Fixture(SiteResolver resolver, StorageLayout layout, SiteAwareMessageSource messages) {
    }

    private static Fixture fixture(Path tmp, int cacheSeconds) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        SiteResolver resolver = new SiteResolver(new SiteStore(layout, new YamlStore()), TestPlatformSettings.empty());
        I18nProperties i18n = new I18nProperties();
        i18n.setCacheSeconds(cacheSeconds);
        return new Fixture(resolver, layout, new SiteAwareMessageSource(resolver, layout, i18n));
    }

    private static Site site(String id) {
        return new Site(id, id, List.of(id + ".test"), EN, List.of(EN, ES), null, SiteStatus.ACTIVE);
    }

    private static void writeBundle(StorageLayout layout, String siteId, String suffix, String body) throws IOException {
        Path file = layout.siteDir(siteId).resolve("i18n").resolve("messages" + suffix + ".properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    @Test
    void resolvesTheSiteBundlePerLocale(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 5);
        writeBundle(f.layout, "acme", "", "about.title=About\n");
        writeBundle(f.layout, "acme", "_es", "about.title=Acerca\n");
        f.resolver.bind(site("acme"));
        assertEquals("About", f.messages.getMessage("about.title", null, EN));
        assertEquals("Acerca", f.messages.getMessage("about.title", null, ES));
    }

    @Test
    void missingKeyThrows(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 5);
        writeBundle(f.layout, "acme", "", "present=yes\n");
        f.resolver.bind(site("acme"));
        assertThrows(NoSuchMessageException.class, () -> f.messages.getMessage("absent.key", null, EN));
    }

    @Test
    void sitesAreIsolated(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 5);
        writeBundle(f.layout, "acme", "", "brand=Acme\n");
        writeBundle(f.layout, "globex", "", "brand=Globex\n");

        f.resolver.bind(site("acme"));
        assertEquals("Acme", f.messages.getMessage("brand", null, EN));
        f.resolver.bind(site("globex"));
        assertEquals("Globex", f.messages.getMessage("brand", null, EN));
    }

    @Test
    void noBoundSiteEchoesTheCode(@TempDir Path tmp) {
        Fixture f = fixture(tmp, 5);
        // no resolver.bind(...)
        assertEquals("any.key", f.messages.getMessage("any.key", null, EN));
    }

    @Test
    void evictRebuildsTheSourceSoEditsAreSeen(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 3600); // long cache so the edit is not seen until eviction
        writeBundle(f.layout, "acme", "", "greeting=V1\n");
        f.resolver.bind(site("acme"));
        assertEquals("V1", f.messages.getMessage("greeting", null, EN));

        writeBundle(f.layout, "acme", "", "greeting=V2\n");
        assertEquals("V1", f.messages.getMessage("greeting", null, EN), "still cached");

        f.messages.evictSite("acme");
        assertEquals("V2", f.messages.getMessage("greeting", null, EN), "eviction re-reads the bundle");
    }

    // ---- stamp-validated bundles, re-checked by every page request -------------------------------

    /** Back-dates a bundle file (and its folder) so the stamp is settled, not racily new. */
    private static void settle(StorageLayout layout, String siteId) throws IOException {
        Path dir = layout.siteDir(siteId).resolve("i18n");
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 3_600_000L));
            }
        }
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 3_600_000L));
    }

    /**
     * The page path: {@link SiteAwareMessageSource#bundleState} re-checks the files on every call, whatever the
     * throttle, and swaps in a fresh source — so what the ETag folds is what the render then reads.
     */
    @Test
    void bundleStateRechecksTheFilesAndTheRenderReadsWhatItDescribes(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 3600);
        writeBundle(f.layout, "acme", "", "greeting=V1\n");
        settle(f.layout, "acme");
        f.resolver.bind(site("acme"));
        SiteAwareMessageSource.BundleState v1 = f.messages.bundleState("acme");
        assertEquals("V1", f.messages.getMessage("greeting", null, EN));
        assertEquals(v1, f.messages.bundleState("acme"), "unchanged files: the same state");

        writeBundle(f.layout, "acme", "", "greeting=V2\n");
        assertEquals("V1", f.messages.getMessage("greeting", null, EN), "an ordinary lookup is throttled");

        SiteAwareMessageSource.BundleState v2 = f.messages.bundleState("acme");
        org.junit.jupiter.api.Assertions.assertNotEquals(v1.fingerprint(), v2.fingerprint());
        assertEquals("V2", f.messages.getMessage("greeting", null, EN), "the re-check swapped the source");
        org.junit.jupiter.api.Assertions.assertTrue(v2.newestModified() >= v1.newestModified());
    }

    /** A locale file created later is noticed (the stamp covers the folder), and so is one deleted. */
    @Test
    void aLocaleFileCreatedOrDeletedLaterIsNoticed(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, 3600);
        writeBundle(f.layout, "acme", "", "greeting=Default\n");
        settle(f.layout, "acme");
        f.resolver.bind(site("acme"));
        SiteAwareMessageSource.BundleState before = f.messages.bundleState("acme");
        assertEquals("Default", f.messages.getMessage("greeting", null, ES));

        writeBundle(f.layout, "acme", "_es", "greeting=Hola\n");
        SiteAwareMessageSource.BundleState created = f.messages.bundleState("acme");
        org.junit.jupiter.api.Assertions.assertNotEquals(before, created);
        assertEquals("Hola", f.messages.getMessage("greeting", null, ES));

        Files.delete(f.layout.siteDir("acme").resolve("i18n").resolve("messages_es.properties"));
        org.junit.jupiter.api.Assertions.assertNotEquals(created, f.messages.bundleState("acme"));
        assertEquals("Default", f.messages.getMessage("greeting", null, ES));
    }

    /** Off the page path a lookup re-checks at most once per {@code cache-seconds} — then sees the edit. */
    @Test
    void anOrdinaryLookupRechecksOncePerInterval(@TempDir Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        SiteResolver resolver = new SiteResolver(new SiteStore(layout, new YamlStore()), TestPlatformSettings.empty());
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        SiteAwareMessageSource messages = new SiteAwareMessageSource(resolver, layout, new I18nProperties(),
                List.of(), now::get); // default 5 s
        writeBundle(layout, "acme", "", "greeting=V1\n");
        settle(layout, "acme");
        assertEquals("V1", messages.forSite("acme").getMessage("greeting", null, EN));

        writeBundle(layout, "acme", "", "greeting=V2\n");
        now.addAndGet(4_999L);
        assertEquals("V1", messages.forSite("acme").getMessage("greeting", null, EN), "inside the interval");
        now.addAndGet(2L);
        assertEquals("V2", messages.forSite("acme").getMessage("greeting", null, EN), "after it");
    }

    /** A plugin's bundle is part of every site's state; one site's own edit is part of only that site's. */
    @Test
    void pluginBundlesFoldIntoEverySiteAndSiteBundlesOnlyIntoTheirOwn(@TempDir Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp.resolve("external"));
        StorageLayout layout = StorageLayout.from(props);
        SiteResolver resolver = new SiteResolver(new SiteStore(layout, new YamlStore()), TestPlatformSettings.empty());
        Path pluginI18n = Files.createDirectories(tmp.resolve("plugins").resolve("fx").resolve("i18n"));
        Files.writeString(pluginI18n.resolve("messages.properties"), "fx.label=Plugin-1\n");
        SiteAwareMessageSource messages = new SiteAwareMessageSource(resolver, layout, new I18nProperties(),
                List.of(pluginI18n), System::currentTimeMillis);
        writeBundle(layout, "acme", "", "greeting=A\n");
        writeBundle(layout, "globex", "", "greeting=G\n");
        var acme = messages.bundleState("acme");
        var globex = messages.bundleState("globex");
        assertEquals("Plugin-1", messages.forSite("acme").getMessage("fx.label", null, EN));

        writeBundle(layout, "acme", "", "greeting=A2\n");
        var acmeEdited = messages.bundleState("acme");
        org.junit.jupiter.api.Assertions.assertNotEquals(acme, acmeEdited);
        assertEquals(globex, messages.bundleState("globex"), "another site's state is untouched");

        Files.writeString(pluginI18n.resolve("messages.properties"), "fx.label=Plugin-22\n");
        org.junit.jupiter.api.Assertions.assertNotEquals(acmeEdited, messages.bundleState("acme"));
        org.junit.jupiter.api.Assertions.assertNotEquals(globex, messages.bundleState("globex"));
        assertEquals("Plugin-22", messages.forSite("globex").getMessage("fx.label", null, EN));
    }

    @Test
    void bundleStateRefusesAnUnsafeSiteId(@TempDir Path tmp) {
        Fixture f = fixture(tmp, 5);
        assertThrows(IllegalArgumentException.class, () -> f.messages.bundleState("../x"));
    }
}
