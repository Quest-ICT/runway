package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform-settings store round-trips the new nested {@code smtp} block through {@code platform.yml}, and a
 * pre-existing file that predates the platform mailer (no {@code smtp:} block) still loads cleanly with
 * {@code smtp == null} — the record-evolution back-compat contract.
 */
class PlatformSettingsSmtpTest {

    private static PlatformSettingsStore store(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
    }

    @Test
    void theSmtpBlockRoundTrips(@TempDir Path root) {
        PlatformSettingsStore store = store(root);
        PlatformSettings.Smtp smtp =
                new PlatformSettings.Smtp("smtp.example.com", 2525, true, "postmaster", "ENC(abc)", "noreply@x.io", true, false);
        store.update(previous -> new PlatformSettings("home", "HIGH", "edge-1", true, false, true, smtp));

        // A fresh store over the same directory re-reads the file (no cache carry-over).
        PlatformSettings read = store(root).get();
        assertEquals("home", read.defaultSite().orElseThrow());
        PlatformSettings.Smtp back = read.smtp();
        assertEquals("smtp.example.com", back.host());
        assertEquals(2525, back.port());
        assertTrue(back.auth());
        assertEquals("postmaster", back.username());
        assertEquals("ENC(abc)", back.password());
        assertEquals("noreply@x.io", back.from());
        assertTrue(back.starttls());
        assertFalse(back.ssl());
        assertTrue(back.isConfigured());
    }

    @Test
    void aPreSmtpFileLoadsWithNullSmtp(@TempDir Path root) throws IOException {
        // A platform.yml written before the platform mailer existed — only the older fields, no smtp: block.
        Path external = root.resolve("external");
        Files.createDirectories(external);
        Files.writeString(external.resolve("platform.yml"),
                "defaultSiteId: \"home\"\npasswordStrength: \"HIGH\"\nnodeName: \"edge-1\"\n");

        PlatformSettings read = store(root).get();
        assertEquals("home", read.defaultSite().orElseThrow(), "old fields still bind");
        assertEquals("edge-1", read.node().orElseThrow());
        assertNull(read.smtp(), "a pre-SMTP file loads with no smtp block (back-compat)");
    }

    @Test
    void theSmtpToStringMasksThePassword() {
        PlatformSettings.Smtp smtp =
                new PlatformSettings.Smtp("h", 25, true, "u", "ENC(secret)", "f@x", false, false);
        assertFalse(smtp.toString().contains("ENC(secret)"), "the (encrypted) password is masked in toString");
        assertTrue(smtp.toString().contains("<set>"));
    }
}
