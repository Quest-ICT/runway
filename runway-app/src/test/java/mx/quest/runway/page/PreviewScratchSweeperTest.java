package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** EH4 — the startup sweep removes orphaned preview scratch files while leaving real pages untouched. */
class PreviewScratchSweeperTest {

    @TempDir
    Path external;

    @Test
    void sweepsLeftoverScratchButKeepsRealPages() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("rw-preview-x.meta.yml"), "path: /x\n");
        Files.writeString(templates.resolve("rw-preview-x.html"), "<html/>");
        Files.writeString(templates.resolve("home.meta.yml"), "path: /\n");
        Files.writeString(templates.resolve("_master.html"), "<html/>");

        new PreviewScratchSweeper(layout).sweep();

        assertFalse(Files.exists(templates.resolve("rw-preview-x.meta.yml")), "scratch meta swept");
        assertFalse(Files.exists(templates.resolve("rw-preview-x.html")), "scratch body swept");
        assertTrue(Files.exists(templates.resolve("home.meta.yml")), "a real page is untouched");
        assertTrue(Files.exists(templates.resolve("_master.html")), "the master is untouched");
    }
}
