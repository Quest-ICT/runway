package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deleting the catch-all default site clears its platform pointer; deleting any other site leaves it. */
class DefaultSiteMaintenanceTest {

    private static PlatformSettingsStore platformIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
    }

    @Test
    void deletingTheDefaultSiteClearsThePointer(@TempDir Path tmp) {
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("def", "HIGH"));
        DefaultSiteMaintenance maintenance = new DefaultSiteMaintenance(platform);

        maintenance.onSiteChanged(new SiteChangedEvent("def", SiteChangedEvent.Kind.DELETED));

        assertTrue(platform.get().defaultSite().isEmpty(), "the dangling default pointer is cleared");
        assertEquals("HIGH", platform.get().strength().orElseThrow(), "other platform settings are preserved");
    }

    @Test
    void deletingANonDefaultSiteLeavesThePointer(@TempDir Path tmp) {
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("def", null));
        DefaultSiteMaintenance maintenance = new DefaultSiteMaintenance(platform);

        maintenance.onSiteChanged(new SiteChangedEvent("other", SiteChangedEvent.Kind.DELETED));

        assertEquals("def", platform.get().defaultSite().orElseThrow(), "an unrelated delete leaves the default");
    }

    @Test
    void deletingASiteDropsItsOwnBandwidthCapAndKeepsTheOthers(@TempDir Path tmp) {
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> new PlatformSettings("def", null).withSiteQuota("gone", 1024L).withSiteQuota("kept", 0L));
        DefaultSiteMaintenance maintenance = new DefaultSiteMaintenance(platform);

        maintenance.onSiteChanged(new SiteChangedEvent("gone", SiteChangedEvent.Kind.DELETED));

        assertEquals(java.util.Map.of("kept", 0L), platform.get().siteQuotas(),
                "a new site with the same id would start from the platform default, not the old cap");
        assertEquals("def", platform.get().defaultSite().orElseThrow(), "the default pointer is untouched");
    }

    @Test
    void deletingASiteDropsItsOwnStorageCeilingAndKeepsTheOthers(@TempDir Path tmp) {
        PlatformSettingsStore platform = platformIn(tmp);
        platform.update(previous -> PlatformSettings.EMPTY.withSiteStorageCeiling("gone", 1024L)
                .withSiteStorageCeiling("kept", 0L));
        DefaultSiteMaintenance maintenance = new DefaultSiteMaintenance(platform);

        maintenance.onSiteChanged(new SiteChangedEvent("gone", SiteChangedEvent.Kind.DELETED));

        assertEquals(java.util.Map.of("kept", 0L), platform.get().siteStorageCeilings(),
                "a new site with the same id would start from the platform default, not the old ceiling");
    }

    @Test
    void storageCeilingsSurviveAReloadAndEveryOtherChange(@TempDir Path tmp) {
        platformIn(tmp).update(previous -> PlatformSettings.EMPTY.withSiteStorageCeiling("acme", 5L << 30));
        // Every with* keeps the ceilings: a later save of another field must not drop them.
        platformIn(tmp).update(current -> current.withSiteQuota("acme", 1L).withDefaultSiteId("acme")
                .withPasswordStrength("HIGH").withSmtp(null).withSettings(java.util.Map.of("a", "b"))
                .withLogLevels(java.util.Map.of("x", "DEBUG")).withEmbedHosts(java.util.List.of()));
        assertEquals(java.util.Map.of("acme", 5L << 30), platformIn(tmp).get().siteStorageCeilings());
    }

    @Test
    void siteCapsSurviveAReloadFromDisk(@TempDir Path tmp) {
        platformIn(tmp).update(previous -> PlatformSettings.EMPTY.withSiteQuota("acme", 10L << 30));
        assertEquals(java.util.Map.of("acme", 10L << 30), platformIn(tmp).get().siteQuotas());
    }
}
