package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery scans, parses and defensively skips bad plugins; duplicate ids are first-wins (sorted). */
class PluginDiscoveryTest {

    private static void writePlugin(Path root, String dirName, String yaml) throws IOException {
        Path dir = Files.createDirectories(root.resolve(dirName));
        Files.writeString(dir.resolve("plugin.yml"), yaml, StandardCharsets.UTF_8);
    }

    @Test
    void aValidPluginIsDiscovered(@TempDir Path root) throws IOException {
        writePlugin(root, "alpha", "id: alpha\nversion: 1.0.0\nmainClass: a.B\n");
        List<LoadedPlugin> found = PluginLoader.discover(root);
        assertEquals(List.of("alpha"), found.stream().map(LoadedPlugin::id).toList());
        assertEquals("alpha", found.get(0).installDir().getFileName().toString());
    }

    @Test
    void aDirectoryWithoutPluginYmlIsSkipped(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("not-a-plugin"));
        writePlugin(root, "real", "id: real\nversion: 1.0.0\n");
        assertEquals(List.of("real"), PluginLoader.discover(root).stream().map(LoadedPlugin::id).toList());
    }

    @Test
    void anInvalidDescriptorIsSkipped(@TempDir Path root) throws IOException {
        writePlugin(root, "bad", "id: bad\nversion: 2.1-RC1\n"); // pre-release version fails validate
        writePlugin(root, "good", "id: good\nversion: 1.0.0\n");
        assertEquals(List.of("good"), PluginLoader.discover(root).stream().map(LoadedPlugin::id).toList());
    }

    @Test
    void aDisabledPluginIsSkipped(@TempDir Path root) throws IOException {
        writePlugin(root, "off", "id: off\nversion: 1.0.0\nenabled: false\n");
        assertTrue(PluginLoader.discover(root).isEmpty());
    }

    // (A "duplicate id" across two folders is now impossible: id must equal the unique directory name, so the
    // first-wins dedup in discover() is defensive-only — see anIdNotMatchingTheDirectoryNameIsRejected.)

    @Test
    void aTraversalIdIsRejected(@TempDir Path root) throws IOException {
        writePlugin(root, "evil", "id: ../escape\nversion: 1.0.0\n");
        assertTrue(PluginLoader.discover(root).isEmpty(), "an unsafe id fails descriptor validation");
    }

    @Test
    void anIdNotMatchingTheDirectoryNameIsRejected(@TempDir Path root) throws IOException {
        // The namespace (/__plugin/<id>, plugin-settings/<id>.yml) and the install dir must agree.
        writePlugin(root, "the-folder", "id: a-different-id\nversion: 1.0.0\n");
        assertTrue(PluginLoader.discover(root).isEmpty(), "id must equal the install-dir name");
    }

    @Test
    void aMissingRootIsEmpty(@TempDir Path root) {
        assertTrue(PluginLoader.discover(root.resolve("does-not-exist")).isEmpty());
        assertTrue(PluginLoader.discover(null).isEmpty());
    }
}
