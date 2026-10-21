package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StorageLayoutTest {

    @Test
    void defaultsDeriveFromExternal() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/srv/runway/external"));
        StorageLayout layout = StorageLayout.from(props);

        assertEquals(Path.of("/srv/runway/external"), layout.external());
        assertEquals(Path.of("/srv/runway/external/sites"), layout.sites());
        assertEquals(Path.of("backups").toAbsolutePath().normalize(), layout.backups());
        assertEquals(Path.of("/srv/runway/external/plugins"), layout.plugins());
    }

    @Test
    void explicitOverridesAreHonored() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/ext"));
        props.setSites(Path.of("/data/sites"));
        props.setBackups(Path.of("/data/backups"));
        StorageLayout layout = StorageLayout.from(props, "/opt/runway-plugins");

        assertEquals(Path.of("/data/sites"), layout.sites());
        assertEquals(Path.of("/data/backups"), layout.backups());
        assertEquals(Path.of("/opt/runway-plugins"), layout.plugins());
        assertEquals(Path.of("/opt/runway-plugins/protection"), layout.pluginDir("protection"),
                "a plugin's install folder follows the configured root");
    }

    @Test
    void aBlankPluginsRootMeansTheDefault() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/ext"));
        assertEquals(Path.of("/ext/plugins"), StorageLayout.from(props, "  ").plugins());
        assertEquals(Path.of("/ext/plugins"), StorageLayout.from(props, null).plugins());
    }

    @Test
    void theSharedPluginsRootRuleIsAbsoluteAndNormalized() {
        assertEquals(Path.of("/x/plugins"), StorageLayout.resolvePluginsRoot("/x/y/../plugins", Path.of("/ext")));
        assertEquals(Path.of("rel").toAbsolutePath().normalize(),
                StorageLayout.resolvePluginsRoot("rel", Path.of("/ext")));
        assertEquals(Path.of("ext/plugins").toAbsolutePath().normalize(),
                StorageLayout.resolvePluginsRoot(null, Path.of("ext")));
    }

    @Test
    void pluginDirRejectsTraversalUnderACustomRoot() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/ext"));
        StorageLayout layout = StorageLayout.from(props, "/opt/plugins");
        assertThrows(IllegalArgumentException.class, () -> layout.pluginDir(".."));
    }

    @Test
    void pathsAreAbsoluteAndNormalized() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("ext/../external"));
        StorageLayout layout = StorageLayout.from(props);
        assertTrue(layout.external().isAbsolute());
        assertEquals(layout.external().normalize(), layout.external());
        assertTrue(layout.external().endsWith("external"));
    }

    @Test
    void siteDirResolvesUnderSitesRoot() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/srv/external"));
        StorageLayout layout = StorageLayout.from(props);
        assertEquals(Path.of("/srv/external/sites/acme"), layout.siteDir("acme"));
    }

    @Test
    void siteDirRejectsTraversal() {
        StorageProperties props = new StorageProperties();
        props.setExternal(Path.of("/srv/external"));
        StorageLayout layout = StorageLayout.from(props);
        assertThrows(IllegalArgumentException.class, () -> layout.siteDir(".."));
        assertThrows(IllegalArgumentException.class, () -> layout.siteDir("a/b"));
    }
}
