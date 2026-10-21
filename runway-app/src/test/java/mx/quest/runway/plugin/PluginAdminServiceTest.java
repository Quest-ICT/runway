package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The drift read model + on-disk lifecycle of {@link PluginAdminService}, exercised against a temp plugins
 * tree and a hand-built {@link RunningPlugins} (the frozen "what loaded this boot" set) — no Spring lifecycle.
 */
class PluginAdminServiceTest {

    private static final long TEST_ENTRY_CAP = 134_217_728L; // 128 MiB — the production default

    private static PluginAdminService service(Path external, String... runningIds) {
        return service(external, TEST_ENTRY_CAP, runningIds);
    }

    private static PluginAdminService service(Path external, long maxEntryBytes, String... runningIds) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Path root = layout.plugins();
        // The frozen runtime captures its LoadedPlugin objects in memory at boot — snapshot eagerly here, so the
        // running set survives a later folder deletion (the removed-but-still-running case).
        List<LoadedPlugin> running = java.util.Arrays.stream(runningIds).map(id -> loadedRef(root, id)).toList();
        return new PluginAdminService(layout, () -> running, maxEntryBytes);
    }

    /** A {@link LoadedPlugin} for an id that is present on disk (so its descriptor reads back). */
    private static LoadedPlugin loadedRef(Path root, String id) {
        try {
            PluginDescriptor descriptor = PluginDescriptor.read(
                    Files.newInputStream(root.resolve(id).resolve("plugin.yml")));
            return new LoadedPlugin(descriptor, root.resolve(id));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeFolder(Path external, String dir, String yaml) throws IOException {
        Path pluginDir = Files.createDirectories(external.resolve("plugins").resolve(dir));
        Files.writeString(pluginDir.resolve("plugin.yml"), yaml, StandardCharsets.UTF_8);
    }

    private static PluginInstallation row(List<PluginInstallation> rows, String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- read model -------------------------------------------------------------------------------------

    @Test
    void listClassifiesEachFolderByItsBadge(@TempDir Path external) throws IOException {
        writeFolder(external, "running-one",
                "id: running-one\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        writeFolder(external, "pending-new",
                "id: pending-new\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        writeFolder(external, "off", "id: off\nversion: 1.0.0\nenabled: false\n");
        writeFolder(external, "broken", "id: broken\nversion: not-a-version\n");

        List<PluginInstallation> rows = service(external, "running-one").list();

        assertEquals(PluginStatus.LOADED, row(rows, "running-one").status());
        assertFalse(row(rows, "running-one").drift());
        assertEquals(PluginStatus.PENDING, row(rows, "pending-new").status(), "new folder not yet running");
        assertTrue(row(rows, "pending-new").drift());
        assertEquals(PluginStatus.DISABLED, row(rows, "off").status());
        assertEquals(PluginStatus.MALFORMED, row(rows, "broken").status());
        assertNotNull(row(rows, "broken").malformedReason());
    }

    // ---- SPI 1.0.0: the preview applies the loader's new refusals -------------------------------------

    /** A plugin with code and no {@code requiresSpi} is previewed Blocked with the pre-1.0 reason, as the loader does. */
    @Test
    void aPreContractPluginIsPreviewedBlocked(@TempDir Path external) throws IOException {
        writeFolder(external, "oldie", "id: oldie\nversion: 1.1.2\nmainClass: a.B\n");
        writeFolder(external, "lib", "id: lib\nversion: 10.4.0\n"); // code-less: exempt

        List<PluginInstallation> rows = service(external).list();

        assertEquals(PluginStatus.BLOCKED, row(rows, "oldie").status());
        assertTrue(row(rows, "oldie").blockedReason().startsWith("was built for the pre-1.0 plugin contract"),
                row(rows, "oldie").blockedReason());
        assertEquals(PluginStatus.PENDING, row(rows, "lib").status(), "a library-only plugin is not affected");
    }

    private static final String SLOPPY = """
            package fixtures.admin.sloppy;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class SloppyConfig {
                @Bean public Object unprefixedThing() { return new Object(); }
            }
            """;

    private static final String HOSTY = """
            package fixtures.admin.hosty;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class HostyConfig {
                public mx.quest.runway.site.SiteStore store;
            }
            """;

    /**
     * The next-boot preview runs the loader's before-refresh surface check (through a throwaway class loader) and the
     * linkage scan: a plugin with an unprefixed {@code @Bean} and a host-linked plugin are Blocked with their reasons,
     * a plugin requiring the first is blocked in cascade, and the host-linked one carries the badge flag — so the
     * screen agrees with what the next boot does. The result is remembered while the jars are unchanged.
     */
    @Test
    void thePreviewRunsTheSurfaceCheckAndTheLinkageScan(@TempDir Path external) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(PluginFixture.compilerAvailable());
        Path plugins = external.resolve("plugins");
        PluginFixture.build(plugins, "sloppy", "id: sloppy\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\n"
                + "mainClass: fixtures.admin.sloppy.SloppyConfig\n", Map.of("fixtures.admin.sloppy.SloppyConfig", SLOPPY));
        PluginFixture.build(plugins, "hosty", "id: hosty\nversion: 1.0.0\nrequiresSpi: \">=0.6.0\"\n"
                + "mainClass: fixtures.admin.hosty.HostyConfig\n", Map.of("fixtures.admin.hosty.HostyConfig", HOSTY));
        writeFolder(external, "needy", "id: needy\nversion: 1.0.0\nrequires: [sloppy]\n");

        PluginAdminService admin = service(external);
        List<PluginInstallation> rows = admin.list();

        assertEquals(PluginStatus.BLOCKED, row(rows, "sloppy").status());
        assertTrue(row(rows, "sloppy").blockedReason().contains("bean name 'unprefixedThing' does not start with "
                + "'sloppy'"), row(rows, "sloppy").blockedReason());
        assertEquals(PluginStatus.BLOCKED, row(rows, "needy").status());
        assertTrue(row(rows, "needy").blockedReason().contains("itself blocked"), row(rows, "needy").blockedReason());
        assertEquals(PluginStatus.BLOCKED, row(rows, "hosty").status());
        assertTrue(row(rows, "hosty").linksHost(), "the badge flag");
        assertTrue(row(rows, "hosty").blockedReason().contains("mx.quest.runway.site.SiteStore"),
                row(rows, "hosty").blockedReason());
        assertFalse(row(rows, "sloppy").linksHost(), "an SPI-only plugin carries no badge");
        assertEquals(rows, admin.list(), "a second read gives the same answer (from the remembered check)");
        assertFalse(admin.restartRequired(), "nothing is running and nothing would load: no drift");
    }

    @Test
    void anUnmetRequirementBlocksWithAReason(@TempDir Path external) throws IOException {
        writeFolder(external, "needy", "id: needy\nversion: 1.0.0\nrequires: ['ghost >= 1.0.0']\n");

        PluginInstallation needy = row(service(external).list(), "needy");

        assertEquals(PluginStatus.BLOCKED, needy.status());
        assertNotNull(needy.blockedReason());
    }

    @Test
    void requiredByListsDependents(@TempDir Path external) throws IOException {
        writeFolder(external, "lib", "id: lib\nversion: 1.0.0\n");
        writeFolder(external, "consumer", "id: consumer\nversion: 1.0.0\nrequires: ['lib']\n");

        assertEquals(List.of("consumer"), service(external).requiredBy("lib"));
        assertEquals(List.of("consumer"), row(service(external).list(), "lib").requiredBy());
        assertTrue(service(external).requiredBy("consumer").isEmpty());
    }

    @Test
    void anIdMismatchIsMalformed(@TempDir Path external) throws IOException {
        writeFolder(external, "the-folder", "id: a-different-id\nversion: 1.0.0\n");
        PluginInstallation rowForFolder = row(service(external).list(), "the-folder");
        assertEquals(PluginStatus.MALFORMED, rowForFolder.status());
        assertFalse(rowForFolder.wellFormed());
    }

    @Test
    void aRunningButRemovedPluginShowsAsPendingUninstall(@TempDir Path external) throws IOException {
        // It loaded this boot, then its folder was deleted: must still appear, flagged as drift/PENDING.
        writeFolder(external, "ghost", "id: ghost\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        PluginAdminService svc = service(external, "ghost");
        Files.delete(external.resolve("plugins").resolve("ghost").resolve("plugin.yml"));
        Files.delete(external.resolve("plugins").resolve("ghost"));

        PluginInstallation ghost = row(svc.list(), "ghost");
        assertFalse(ghost.present());
        assertTrue(ghost.loaded());
        assertTrue(ghost.drift());
        assertEquals(PluginStatus.PENDING, ghost.status());
    }

    @Test
    void restartRequiredReflectsTheDriftBetweenDiskAndRunning(@TempDir Path external) throws IOException {
        writeFolder(external, "a", "id: a\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        assertTrue(service(external).restartRequired(), "disk has 'a' but nothing is running");
        assertFalse(service(external, "a").restartRequired(), "disk and running agree on {a}");

        writeFolder(external, "b", "id: b\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        assertTrue(service(external, "a").restartRequired(), "new 'b' on disk, only 'a' running");
    }

    // ---- enable / disable -------------------------------------------------------------------------------

    @Test
    void setEnabledRewritesOnlyTheEnabledLine(@TempDir Path external) throws IOException {
        writeFolder(external, "p",
                "# a comment\nid: p\nversion: 1.0.0\nenabled: true\nrequiresSpi: \">=1.0.0\"\nmainClass: a.B\n");
        service(external).setEnabled("p", false);

        List<String> lines = Files.readAllLines(external.resolve("plugins/p/plugin.yml"));
        assertTrue(lines.contains("# a comment"), "comment preserved");
        assertTrue(lines.contains("id: p"), "other keys preserved");
        assertTrue(lines.contains("enabled: false"), "enabled flipped");
        assertFalse(lines.contains("enabled: true"));
        // and the disabled plugin now classifies as DISABLED
        assertEquals(PluginStatus.DISABLED, row(service(external).list(), "p").status());
    }

    @Test
    void setEnabledAppendsTheKeyWhenAbsent(@TempDir Path external) throws IOException {
        writeFolder(external, "p", "id: p\nversion: 1.0.0\n");
        service(external).setEnabled("p", false);
        assertTrue(Files.readAllLines(external.resolve("plugins/p/plugin.yml")).contains("enabled: false"));
    }

    @Test
    void setEnabledOnlyTargetsTheTopLevelKeyNotANestedOne(@TempDir Path external) throws IOException {
        // A nested `enabled:` (indented, e.g. under a settings entry) must not be mistaken for the top-level flag.
        writeFolder(external, "p", "id: p\nversion: 1.0.0\nsettings:\n  - key: x\n    enabled: true\n");
        service(external).setEnabled("p", false);
        List<String> lines = Files.readAllLines(external.resolve("plugins/p/plugin.yml"));
        assertTrue(lines.contains("    enabled: true"), "the nested enabled: is untouched");
        assertTrue(lines.contains("enabled: false"), "a top-level enabled: was appended");
    }

    @Test
    void setEnabledRejectsAnUnknownPlugin(@TempDir Path external) {
        assertThrows(IllegalArgumentException.class, () -> service(external).setEnabled("nope", true));
    }

    // ---- uninstall --------------------------------------------------------------------------------------

    @Test
    void uninstallRemovesTheFolder(@TempDir Path external) throws IOException {
        writeFolder(external, "p", "id: p\nversion: 1.0.0\n");
        service(external).uninstall("p");
        assertFalse(Files.exists(external.resolve("plugins/p")));
    }

    @Test
    void uninstallRejectsUnknownAndUnsafeIds(@TempDir Path external) {
        assertThrows(IllegalArgumentException.class, () -> service(external).uninstall("missing"));
        assertThrows(IllegalArgumentException.class, () -> service(external).uninstall("../escape"));
    }

    @Test
    void essentialPluginCannotBeDisabledOrUninstalled(@TempDir Path external) throws IOException {
        writeFolder(external, "protection", "id: protection\nname: Protection\nversion: 1.0.0\nessential: true\n");
        PluginAdminService service = service(external, "protection");

        assertThrows(IllegalArgumentException.class, () -> service.setEnabled("protection", false),
                "an essential security-floor plugin must not be disable-able");
        assertThrows(IllegalArgumentException.class, () -> service.uninstall("protection"),
                "an essential security-floor plugin must not be uninstallable");
        // Neither mutation touched the folder or the enabled: line.
        assertTrue(Files.exists(external.resolve("plugins/protection/plugin.yml")));
        assertFalse(Files.readString(external.resolve("plugins/protection/plugin.yml")).contains("enabled: false"));

        // Enabling it (a no-op refresh) is allowed, and the row flags it essential for the UI.
        service.setEnabled("protection", true);
        assertTrue(row(service.list(), "protection").essential(), "row marks the allowlisted plugin essential");
    }

    @Test
    void nonEssentialPluginCanStillBeDisabledAndUninstalled(@TempDir Path external) throws IOException {
        writeFolder(external, "demo", "id: demo\nversion: 1.0.0\n");
        assertFalse(row(service(external).list(), "demo").essential());
        service(external).setEnabled("demo", false);
        assertTrue(Files.readString(external.resolve("plugins/demo/plugin.yml")).contains("enabled: false"));
        service(external).uninstall("demo");
        assertFalse(Files.exists(external.resolve("plugins/demo")));
    }

    // ---- install ----------------------------------------------------------------------------------------

    @Test
    void installExtractsValidatesAndPlacesUnderTheId(@TempDir Path external) {
        byte[] zip = zip(Map.of(
                "myplugin/plugin.yml", "id: myplugin\nversion: 2.1.0\nmainClass: a.B\n",
                "myplugin/myplugin.jar", "JARBYTES"));
        PluginDescriptor descriptor = service(external).install(new ByteArrayInputStream(zip));

        assertEquals("myplugin", descriptor.id());
        assertTrue(Files.exists(external.resolve("plugins/myplugin/plugin.yml")));
        assertTrue(Files.exists(external.resolve("plugins/myplugin/myplugin.jar")));
    }

    @Test
    void installAcceptsADescriptorAtTheArchiveRoot(@TempDir Path external) {
        byte[] zip = zip(Map.of("plugin.yml", "id: flat\nversion: 1.0.0\n"));
        assertEquals("flat", service(external).install(new ByteArrayInputStream(zip)).id());
        assertTrue(Files.exists(external.resolve("plugins/flat/plugin.yml")));
    }

    @Test
    void installNormalizesTheFolderNameToTheId(@TempDir Path external) {
        // A wrapping dir named differently from the id must still land under plugins/<id>/.
        byte[] zip = zip(Map.of("wrongname/plugin.yml", "id: realid\nversion: 1.0.0\n"));
        service(external).install(new ByteArrayInputStream(zip));
        assertTrue(Files.exists(external.resolve("plugins/realid/plugin.yml")));
        assertFalse(Files.exists(external.resolve("plugins/wrongname")));
    }

    @Test
    void installUpgradeReplacesPriorContent(@TempDir Path external) throws IOException {
        writeFolder(external, "p", "id: p\nversion: 1.0.0\n");
        Files.writeString(external.resolve("plugins/p/old.txt"), "stale");
        byte[] zip = zip(Map.of("p/plugin.yml", "id: p\nversion: 2.0.0\n"));
        service(external).install(new ByteArrayInputStream(zip));

        assertFalse(Files.exists(external.resolve("plugins/p/old.txt")), "prior content cleared on upgrade");
        assertTrue(Files.readString(external.resolve("plugins/p/plugin.yml")).contains("2.0.0"));
    }

    /**
     * An upload whose descriptor claims an essential id ({@code protection}) used to replace the
     * essential plugin — and inherit its exemptions (no linkage scan, no surface check, cannot be disabled or
     * uninstalled). Essential plugins come only from the Runway installer/seed: the upload is refused before anything
     * on disk is touched, whatever the archive's folder is called and whatever else the descriptor says.
     */
    @Test
    void installRefusesAnEssentialIdAndLeavesTheEssentialPluginUntouched(@TempDir Path external) throws IOException {
        writeFolder(external, "protection", "id: protection\nversion: 1.4.1\nessential: true\n");
        Files.writeString(external.resolve("plugins/protection/protection.jar"), "the shipped build");
        PluginAdminService service = service(external);
        for (Map<String, String> upload : List.of(
                Map.of("protection/plugin.yml", "id: protection\nversion: 9.0.0\nessential: true\n",
                        "protection/protection.jar", "SOMEONE ELSE'S BYTES"),
                Map.of("plugin.yml", "id: protection\nversion: 1.4.1\n"),
                Map.of("innocent/plugin.yml", "id: protection\nversion: 1.4.2\n"))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> service.install(new ByteArrayInputStream(zip(upload))));
            assertTrue(refused.getMessage().contains("essential") && refused.getMessage().contains("installer"),
                    refused.getMessage());
            assertEquals("the shipped build",
                    Files.readString(external.resolve("plugins/protection/protection.jar")), "untouched");
            assertTrue(Files.readString(external.resolve("plugins/protection/plugin.yml")).contains("1.4.1"));
            assertFalse(Files.exists(external.resolve("plugins/protection.installing")), "nothing staged");
            assertFalse(Files.exists(external.resolve("plugins/innocent")));
        }
        // ...also when the essential plugin is absent (it is not "installed" from the console either)
        Path empty = Files.createDirectories(external.resolveSibling(external.getFileName() + "-empty"));
        assertThrows(IllegalArgumentException.class, () -> service(empty).install(new ByteArrayInputStream(
                zip(Map.of("plugin.yml", "id: protection\nversion: 1.4.1\n")))));
        assertFalse(Files.exists(empty.resolve("plugins/protection")));
    }

    @Test
    void aFailedUpgradeLeavesThePriorVersionIntactWithNoStagingLeftover(@TempDir Path external) throws IOException {
        writeFolder(external, "p", "id: p\nversion: 1.0.0\n");
        Files.writeString(external.resolve("plugins/p/keep.txt"), "v1");
        // The upgrade's descriptor is invalid → validation throws BEFORE the existing folder is touched.
        byte[] zip = zip(Map.of("p/plugin.yml", "id: p\nversion: not-a-version\n"));
        assertThrows(IllegalArgumentException.class,
                () -> service(external).install(new ByteArrayInputStream(zip)));
        assertTrue(Files.exists(external.resolve("plugins/p/keep.txt")), "prior version intact after a failed install");
        assertFalse(Files.exists(external.resolve("plugins/p.installing")), "no staging directory leaked");
    }

    @Test
    void installRejectsAnArchiveWithoutADescriptor(@TempDir Path external) {
        byte[] zip = zip(Map.of("readme.txt", "no descriptor here"));
        assertThrows(IllegalArgumentException.class,
                () -> service(external).install(new ByteArrayInputStream(zip)));
    }

    @Test
    void installRejectsAnInvalidDescriptor(@TempDir Path external) {
        byte[] zip = zip(Map.of("plugin.yml", "id: bad\nversion: not-a-version\n"));
        assertThrows(IllegalArgumentException.class,
                () -> service(external).install(new ByteArrayInputStream(zip)));
        assertFalse(Files.exists(external.resolve("plugins/bad")), "nothing committed on a bad descriptor");
    }

    @Test
    void installRejectsAZipSlipEntry(@TempDir Path external) {
        byte[] zip = zip(Map.of(
                "plugin.yml", "id: ok\nversion: 1.0.0\n",
                "../escape.txt", "pwned"));
        assertThrows(IllegalArgumentException.class,
                () -> service(external).install(new ByteArrayInputStream(zip)));
    }

    @Test
    void installRejectsADescriptorWithAnUnsafeId(@TempDir Path external) {
        byte[] zip = zip(Map.of("plugin.yml", "id: ../escape\nversion: 1.0.0\n"));
        assertThrows(IllegalArgumentException.class,
                () -> service(external).install(new ByteArrayInputStream(zip)));
    }

    @Test
    void installRejectsAnEntryThatExceedsThePerEntryCap(@TempDir Path external) {
        // A 128-byte cap stands in for the 128 MiB production default; the payload entry blows past it, so a
        // decompression-bomb archive is refused mid-extraction rather than filling the staging disk.
        byte[] zip = zip(Map.of(
                "myplugin/plugin.yml", "id: myplugin\nversion: 1.0.0\n",
                "myplugin/payload.bin", "x".repeat(500)));
        assertThrows(java.io.UncheckedIOException.class,
                () -> service(external, 128L).install(new ByteArrayInputStream(zip)));
        assertFalse(Files.exists(external.resolve("plugins/myplugin")), "nothing committed when an entry overflows the cap");
    }

    @Test
    void aBareKernelListsNothing(@TempDir Path external) {
        assertTrue(service(external).list().isEmpty());
        assertNull(emptyOrNull(service(external).requiredBy("anything")));
        assertFalse(service(external).restartRequired());
    }

    // ---- runway.plugins.root ----------------------------------------------------------------------------

    /**
     * With {@code runway.plugins.root} set, every console operation — install, list, disable, enable, uninstall —
     * works in that folder, and nothing is ever written to {@code <external>/plugins} (where the loader would not
     * look). The regression: the console used to keep installing under {@code <external>/plugins}.
     */
    @Test
    void aConfiguredPluginsRootIsWhereTheConsoleInstallsListsTogglesAndUninstalls(@TempDir Path tmp)
            throws IOException {
        Path external = Files.createDirectories(tmp.resolve("external"));
        Path customRoot = tmp.resolve("custom-plugins");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props, customRoot.toString());
        PluginAdminService service = new PluginAdminService(layout, List::of, TEST_ENTRY_CAP);

        service.install(new ByteArrayInputStream(zip(Map.of("x/plugin.yml", "id: x\nversion: 1.0.0\n"))));
        assertTrue(Files.exists(customRoot.resolve("x/plugin.yml")), "installed under the configured root");
        assertEquals(List.of("x"), service.list().stream().map(PluginInstallation::id).toList(),
                "listed from the configured root");

        service.setEnabled("x", false);
        assertTrue(Files.readString(customRoot.resolve("x/plugin.yml")).contains("enabled: false"));
        service.setEnabled("x", true);
        assertFalse(Files.readString(customRoot.resolve("x/plugin.yml")).contains("enabled: false"));

        service.uninstall("x");
        assertFalse(Files.exists(customRoot.resolve("x")), "uninstalled from the configured root");
        assertFalse(Files.exists(external.resolve("plugins")), "nothing was ever written under <external>/plugins");
    }

    /** The loader and the console resolve the SAME folder from the same environment — custom root and default. */
    @Test
    void theLoaderAndTheConsoleAgreeOnThePluginsRoot(@TempDir Path tmp) {
        org.springframework.mock.env.MockEnvironment custom = new org.springframework.mock.env.MockEnvironment()
                .withProperty("runway.storage.external", tmp.resolve("ext").toString())
                .withProperty("runway.plugins.root", tmp.resolve("elsewhere").toString());
        assertEquals(PluginContextInitializer.pluginsRoot(custom), layoutFor(custom).plugins());
        assertEquals(tmp.resolve("elsewhere").toAbsolutePath().normalize(), layoutFor(custom).plugins());

        org.springframework.mock.env.MockEnvironment defaults = new org.springframework.mock.env.MockEnvironment()
                .withProperty("runway.storage.external", tmp.resolve("ext").toString());
        assertEquals(PluginContextInitializer.pluginsRoot(defaults), layoutFor(defaults).plugins());
        assertEquals(tmp.resolve("ext/plugins").toAbsolutePath().normalize(), layoutFor(defaults).plugins());
    }

    /** The StorageLayout bean as {@code StorageConfig} builds it from an environment. */
    private static StorageLayout layoutFor(org.springframework.core.env.Environment env) {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of(env.getProperty("runway.storage.external")));
        return new mx.quest.runway.storage.StorageConfig().storageLayout(props, env);
    }

    private static <T> List<T> emptyOrNull(List<T> list) {
        return list.isEmpty() ? null : list;
    }

    /** Builds a zip from entry-name → text content (a directory entry is implied by the path). */
    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }
}
