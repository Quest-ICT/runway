package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteResolverTest {

    private static final Locale EN = Locale.ENGLISH;

    private static SiteStore storeIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new SiteStore(StorageLayout.from(props), new YamlStore());
    }

    private static PlatformSettingsStore platformIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
    }

    private static Site site(String id, String... hosts) {
        return new Site(id, id, List.of(hosts), EN, List.of(EN), null, SiteStatus.ACTIVE);
    }

    @Test
    void resolvesByHostIgnoringCaseAndPort(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        store.save(site("acme", "Acme.Test"));
        SiteResolver resolver = new SiteResolver(store, TestPlatformSettings.empty());
        assertEquals("acme", resolver.resolve("acme.test:8443").orElseThrow().id());
        assertEquals("acme", resolver.resolve("ACME.TEST").orElseThrow().id());
    }

    @Test
    void unknownAndNullHostResolveEmptyWithNoDefault(@TempDir Path tmp) {
        SiteResolver resolver = new SiteResolver(storeIn(tmp), TestPlatformSettings.empty());
        assertTrue(resolver.resolve("nope.test").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
    }

    @Test
    void unmatchedHostFallsBackToTheConfiguredDefaultSite(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        store.save(site("acme", "acme.test"));
        store.save(site("def")); // no hosts — reachable only as the catch-all default
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("def", null));
        SiteResolver resolver = new SiteResolver(store, platform);

        assertEquals("acme", resolver.resolve("acme.test").orElseThrow().id(), "an explicit host still wins");
        assertEquals("def", resolver.resolve("nobody.example").orElseThrow().id(), "an unmatched host falls back");
        assertEquals("def", resolver.resolve(null).orElseThrow().id(), "a null host falls back too");
    }

    @Test
    void aDanglingDefaultLeavesUnmatchedHostsUnresolved(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("ghost", null)); // points at a site that does not exist
        SiteResolver resolver = new SiteResolver(store, platform);
        assertTrue(resolver.resolve("whatever.test").isEmpty(), "a dangling default id resolves to empty (404)");
    }

    @Test
    void aSuspendedDefaultDoesNotCatchAllUnmatchedHosts(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        store.save(new Site("def", "def", List.of(), EN, List.of(EN), null, SiteStatus.SUSPENDED));
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("def", null));
        SiteResolver resolver = new SiteResolver(store, platform);
        assertTrue(resolver.resolve("random.example").isEmpty(),
                "a suspended default is not a catch-all — unmatched hosts 404 rather than 503");
    }

    @Test
    void currentBindAndClear(@TempDir Path tmp) {
        SiteResolver resolver = new SiteResolver(storeIn(tmp), TestPlatformSettings.empty());
        assertTrue(resolver.current().isEmpty());
        resolver.bind(site("acme", "acme.test"));
        assertEquals("acme", resolver.current().orElseThrow().id());
        resolver.clear();
        assertTrue(resolver.current().isEmpty());
    }

    @Test
    void indexIsCachedUntilSiteChangedEvent(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        SiteResolver resolver = new SiteResolver(store, TestPlatformSettings.empty());
        assertTrue(resolver.resolve("late.test").isEmpty()); // builds + caches an index without it

        store.save(site("late", "late.test"));
        assertTrue(resolver.resolve("late.test").isEmpty(), "cached index does not see the new site yet");

        resolver.onSiteChanged(new SiteChangedEvent("late", SiteChangedEvent.Kind.CREATED));
        assertEquals("late", resolver.resolve("late.test").orElseThrow().id(), "index rebuilt after the event");
    }
}
