package mx.quest.runway.spi.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SPI 0.5.0 fakes apply the kernel's input rules, so a plugin tested against them behaves the same live. */
class K2FakesTest {

    @TempDir
    Path sites;

    @Test
    void tempDirSiteFilesWritesUnderTheKernelLayoutAndRefusesEscapes() throws IOException {
        TempDirSiteFiles files = new TempDirSiteFiles(sites, "contact", 32);
        Files.createDirectories(sites.resolve("acme"));
        Files.writeString(sites.resolve("acme/users.yml"), "SECRET");
        files.writeText("acme", "submissions.tsv", "row");
        assertEquals("row", Files.readString(sites.resolve("acme/plugins/contact/submissions.tsv")));
        assertEquals(Optional.of("row"), files.readText("acme", "submissions.tsv"));
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "../../users.yml"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "big", "x".repeat(33)));
        assertThrows(IOException.class, () -> files.writeText("nosite", "a", "x"), "the site must exist");
        Files.createSymbolicLink(files.root("acme").resolve("out"), sites.resolve("acme"));
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "out/users.yml"));
        assertEquals(List.of("submissions.tsv"), files.list("acme", "").stream().map(e -> e.name()).toList());
        assertTrue(files.delete("acme", "submissions.tsv"));
        assertFalse(files.deleteTree("acme", "nothing"));
    }

    @Test
    void tempDirSiteFilesTellsCannotSearchFromAbsentLikeTheKernel() throws IOException {
        TempDirSiteFiles files = new TempDirSiteFiles(sites, "contact", 32);
        Files.createDirectories(sites.resolve("acme"));
        files.writeText("acme", "dir/a.txt", "x");
        Path dir = sites.resolve("acme").resolve("plugins").resolve("contact").resolve("dir");
        var before = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, java.util.Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(dir.resolve("a.txt")),
                    "running as a user that ignores permissions");
            assertThrows(IOException.class, () -> files.stat("acme", "dir/a.txt"));
            assertThrows(IOException.class, () -> files.list("acme", "dir"));
            assertEquals(Optional.empty(), files.stat("acme", "nope"));
            assertEquals(List.of(), files.list("acme", "nope"));
        } finally {
            Files.setPosixFilePermissions(dir, before);
        }
    }

    @Test
    void fakeSiteServicesBehaveLikeTheKernel() {
        SiteView acme = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
        FakeSiteServices kernel = new FakeSiteServices().site(acme).grant("contact|settings")
                .message("acme", "contact.sent", "Thanks, {0}!").message(null, "contact.title", "Contact");
        assertEquals(Optional.of(acme), kernel.find("acme"));
        assertEquals(Optional.empty(), kernel.find("other"));
        assertThrows(IllegalArgumentException.class, () -> kernel.find("../x"));
        assertEquals(Optional.empty(), kernel.site(), "off a request until current() is set");
        assertEquals(Optional.of(acme), kernel.current("acme").site());
        assertTrue(kernel.may("contact|settings"));
        assertFalse(kernel.may("EDIT_PAGES"), "fail-closed");
        assertEquals("Thanks, Ana!", kernel.site("acme", Locale.ENGLISH, "contact.sent", "fb", "Ana"));
        assertEquals("fb", kernel.site("acme", Locale.ENGLISH, "missing", "fb"));
        assertEquals("missing", kernel.console("missing", null));
        assertEquals("Contact", kernel.console("contact.title", "fb"));

        StarterPage page = new StarterPage("contact", "/contact", Map.of("en", "Contact"), "<p/>", null, false);
        kernel.nextInstall(InstallResult.Outcome.DRAFT_BLOCKED, Finding.error("x", "y"));
        InstallResult first = kernel.install("acme", page);
        assertEquals(InstallResult.Outcome.DRAFT_BLOCKED, first.outcome());
        assertEquals(1, first.blocking().size());
        assertEquals(InstallResult.Outcome.ALREADY_EXISTS, kernel.install("acme", page).outcome(), "never overwrites");
        assertEquals(Optional.of("/contact"), kernel.pathOf("acme", "contact"));
        assertEquals(2, kernel.installs().size());
    }
}
