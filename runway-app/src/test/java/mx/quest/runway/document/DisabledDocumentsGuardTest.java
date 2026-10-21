package mx.quest.runway.document;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guard that withholds disabled documents from public serving. */
class DisabledDocumentsGuardTest {

    @TempDir
    Path external;
    private DocumentService docs;
    private DisabledDocumentsGuard guard;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        docs = new DocumentService(new SiteFileStore(layout), layout, new YamlStore(), new mx.quest.runway.site.SiteStorageCeilings(
                new mx.quest.runway.platform.PlatformSettingsStore(layout, new YamlStore()), layout, 0));
        guard = new DisabledDocumentsGuard(docs);
    }

    @Test
    void aDisabledDocumentIsWithheldAnEnabledOneIsNot() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        assertFalse(guard.isWithheld("acme", "doc/report.pdf"), "enabled documents serve");
        docs.setDisabled("acme", "report.pdf", true);
        assertTrue(guard.isWithheld("acme", "doc/report.pdf"), "disabled documents are withheld");
    }

    @Test
    void onlyTheDocSubtreeIsSubjectToTheGuard() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        docs.setDisabled("acme", "report.pdf", true);
        assertFalse(guard.isWithheld("acme", "css/site.css"), "non-doc assets are never withheld here");
        assertFalse(guard.isWithheld("acme", "img/logo.png"), "non-doc assets are never withheld here");
    }
}
