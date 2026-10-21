package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mx.quest.runway.RunwayApplication;
import mx.quest.runway.plugin.PluginContextInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * The startup hook for the data-layout marker: where it runs, which folder it checks, how WIZARD mode is
 * respected, and that a refused layout stops the real application before anything writes into the folder.
 */
class DataLayoutInitializerTest {

    @TempDir
    Path root;

    private static GenericApplicationContext contextWith(MockEnvironment env) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(env);
        return context;
    }

    private static MockEnvironment active(Path external) {
        return new MockEnvironment()
                .withProperty("runway.storage.external", external.toString())
                .withProperty("runway.security.app-master-id", "app-master");
    }

    private static MockEnvironment wizard(Path external) {
        return new MockEnvironment().withProperty("runway.storage.external", external.toString());
    }

    @Test
    void activeModeWritesTheMarkerEvenIntoAFolderThatDoesNotExistYet() {
        Path external = root.resolve("external");

        new DataLayoutInitializer().initialize(contextWith(active(external)));

        assertTrue(Files.isRegularFile(external.resolve("layout.yml")));
    }

    /** A fresh install's first (WIZARD) boot: no folder, nothing to protect — and nothing created on its behalf. */
    @Test
    void wizardModeAgainstAMissingFolderWritesNothing() {
        Path external = root.resolve("external");

        new DataLayoutInitializer().initialize(contextWith(wizard(external)));

        assertFalse(Files.exists(external));
    }

    @Test
    void wizardModeAgainstAnExistingFolderWritesTheMarker() throws IOException {
        Path external = Files.createDirectories(root.resolve("external"));

        new DataLayoutInitializer().initialize(contextWith(wizard(external)));

        assertTrue(Files.isRegularFile(external.resolve("layout.yml")));
    }

    @Test
    void aBlankPinIsWizardMode() {
        Path external = root.resolve("external");
        MockEnvironment env = wizard(external).withProperty("runway.security.app-master-id", "  ");

        new DataLayoutInitializer().initialize(contextWith(env));

        assertFalse(Files.exists(external));
    }

    @Test
    void aNewerMarkerAbortsInitialisationInEitherMode() throws IOException {
        Path external = Files.createDirectories(root.resolve("external"));
        Files.writeString(external.resolve("layout.yml"), "version: 2\n");

        assertThrows(DataLayoutException.class,
                () -> new DataLayoutInitializer().initialize(contextWith(active(external))));
        assertThrows(DataLayoutException.class,
                () -> new DataLayoutInitializer().initialize(contextWith(wizard(external))));
    }

    /** The folder is resolved by the same binding as the StorageLayout bean, so relaxed names work too. */
    @Test
    void theFolderIsResolvedThroughRelaxedBinding() {
        Path external = root.resolve("relaxed");
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("RUNWAY_STORAGE_EXTERNAL", external.toString())));

        Path resolved = DataLayoutInitializer.externalFolder(env);
        StorageProperties props = new StorageProperties();
        props.setExternal(external);

        assertEquals(StorageLayout.from(props).external(), resolved);
    }

    @Test
    void theDefaultFolderMatchesTheStorageLayoutDefault() {
        assertEquals(StorageLayout.from(new StorageProperties()).external(),
                DataLayoutInitializer.externalFolder(new MockEnvironment()));
    }

    /** It must run before the plugin loader reads external/ (and is ordered ahead of every unordered initializer). */
    @Test
    @SuppressWarnings("rawtypes")
    void itIsRegisteredAndRunsBeforeThePluginLoader() {
        List<ApplicationContextInitializer> initializers = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(ApplicationContextInitializer.class);
        AnnotationAwareOrderComparator.sort(initializers);

        int layout = indexOf(initializers, DataLayoutInitializer.class);
        int plugins = indexOf(initializers, PluginContextInitializer.class);
        assertTrue(layout >= 0, "DataLayoutInitializer is registered in spring.factories");
        assertTrue(plugins >= 0, "PluginContextInitializer is registered in spring.factories");
        assertTrue(layout < plugins, "the layout check precedes the plugin loader: " + initializers);
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, new DataLayoutInitializer().getOrder());
    }

    @SuppressWarnings("rawtypes")
    private static int indexOf(List<ApplicationContextInitializer> list, Class<?> type) {
        for (int i = 0; i < list.size(); i++) {
            if (type.isInstance(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void theFailureAnalyzerExplainsBothRefusals() throws IOException {
        Path external = Files.createDirectories(root.resolve("external"));
        Path marker = external.resolve("layout.yml");

        Files.writeString(marker, "version: 7\n");
        DataLayoutException newer = assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, true));
        FailureAnalysis analysis = new DataLayoutFailureAnalyzer().analyze(new IllegalStateException("wrap", newer));
        assertTrue(analysis.getDescription().contains("version 7"), analysis.getDescription());
        assertTrue(analysis.getAction().contains("release that wrote this folder"), analysis.getAction());

        Files.writeString(marker, "version: nope\n");
        DataLayoutException broken = assertThrows(DataLayoutException.class, () -> DataLayout.verify(external, true));
        FailureAnalysis brokenAnalysis = new DataLayoutFailureAnalyzer().analyze(broken);
        assertTrue(brokenAnalysis.getAction().contains("version: 1"), brokenAnalysis.getAction());
    }

    // --- the real application ----------------------------------------------------------------------------------

    /**
     * End to end: the real application, started against a folder a newer Runway wrote, refuses to start — and the
     * folder still holds only the marker, which proves the guard ran before any store, seeder or plugin wrote.
     */
    @Test
    void theApplicationRefusesToStartOnANewerLayoutBeforeWritingAnything() throws IOException {
        Path external = Files.createDirectories(root.resolve("newer"));
        Files.writeString(external.resolve("layout.yml"), "version: 2\n");

        Exception failed = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RunwayApplication.class)
                    .properties("runway.storage.external=" + external,
                            "runway.storage.backups=" + root.resolve("backups"),
                            "runway.security.app-master-id=app-master",
                            "server.port=0")
                    .run()) {
                // unreachable: the layout is newer than this build
            }
        });

        assertInstanceOf(DataLayoutException.class, rootCauseOrSelf(failed, DataLayoutException.class), failed.toString());
        try (Stream<Path> entries = Files.list(external)) {
            assertEquals(List.of(external.resolve("layout.yml")), entries.toList(),
                    "nothing but the marker may exist after a refused start");
        }
        assertEquals("version: 2\n", Files.readString(external.resolve("layout.yml")));
    }

    /** End to end: a first WIZARD boot against an existing (installer-made) folder stamps it as layout 1. */
    @Test
    void theApplicationWritesTheMarkerOnStart() throws IOException {
        Path external = Files.createDirectories(root.resolve("fresh"));

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RunwayApplication.class)
                .properties("runway.storage.external=" + external,
                        "runway.storage.backups=" + root.resolve("backups"),
                        "server.port=0")
                .run()) {
            assertTrue(ctx.isActive());
        }

        assertEquals(1, DataLayout.read(external.resolve("layout.yml")));
    }

    private static Throwable rootCauseOrSelf(Throwable thrown, Class<?> wanted) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (wanted.isInstance(t)) {
                return t;
            }
        }
        return thrown;
    }
}
