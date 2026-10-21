package mx.quest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * {@link NoResultsLog}: off by default, normalization, the personal-data filter, the repeat window, the term cap
 * and its eviction order, retention, persistence to the plugin's own per-site file (through {@code PluginSiteFiles}),
 * clear, tenant isolation, the wholesale-replacement reload, tolerant loading, an unreadable file never overwritten,
 * and that a term never reaches a log line.
 */
class NoResultsLogTest {

    @TempDir
    Path root;

    private PluginSiteFiles files;
    private FakePluginSettings settings;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("acme"));
        Files.createDirectories(root.resolve("beta"));
        files = new TempDirSiteFiles(root, "search");
        settings = new FakePluginSettings()
                .defaults(Map.of("recordNoResults", false, "noResultsRetentionDays", 90L))
                .site("acme", Map.of("recordNoResults", true))
                .site("beta", Map.of("recordNoResults", true));
    }

    private NoResultsLog log() {
        return new NoResultsLog(files, settings, clock, false);
    }

    /** Records {@code term} as a separate search (past the repeat window). */
    private void search(NoResultsLog log, String site, String term) {
        clock.advance(Duration.ofSeconds(2));
        log.record(site, term);
    }

    private Path file(String site) {
        return root.resolve(site).resolve("plugins/search").resolve(NoResultsLog.FILE);
    }

    @Test
    void recordingIsOffUnlessTheSiteOptsIn() {
        settings.site("acme", Map.of());
        NoResultsLog log = log();
        search(log, "acme", "pricing");
        log.flush();
        assertTrue(log.list("acme").isEmpty());
        assertFalse(Files.exists(file("acme")), "nothing is written for a site that did not opt in");
        assertFalse(log.enabled("acme"));

        settings.site("acme", Map.of("recordNoResults", false));
        search(log, "acme", "pricing");
        assertTrue(log.list("acme").isEmpty());
    }

    @Test
    void variantsOfOneTermAreCountedTogether() {
        NoResultsLog log = log();
        search(log, "acme", "  Opening   Hours ");
        search(log, "acme", "opening hours");
        search(log, "acme", "OPENING\tHOURS\n");
        search(log, "acme", "opening hours");

        List<NoResultsLog.Entry> list = log.list("acme");
        assertEquals(1, list.size());
        assertEquals("opening hours", list.get(0).term());
        assertEquals(4, list.get(0).count());
        assertEquals(clock.instant(), list.get(0).lastSeen());
    }

    @Test
    void normalizationRulesAndLengthCap() {
        assertNull(NoResultsLog.normalize(null));
        assertNull(NoResultsLog.normalize("   \t\n "));
        assertNull(NoResultsLog.normalize("\u0000​"), "control and format characters only — nothing left");
        assertEquals("café", NoResultsLog.normalize("Café"), "NFC folds the combining accent");
        assertEquals("a b", NoResultsLog.normalize("a\u0007 \u0000b"));
        String longTerm = NoResultsLog.normalize("x".repeat(300));
        assertEquals(NoResultsLog.MAX_TERM_CHARS, longTerm.length());
        String surrogate = NoResultsLog.normalize("y".repeat(NoResultsLog.MAX_TERM_CHARS - 1) + "😀");
        assertEquals("y".repeat(NoResultsLog.MAX_TERM_CHARS - 1), surrogate, "never half a code point");
    }

    @Test
    void termsThatLookLikePersonalDataAreNeverStored() {
        NoResultsLog log = log();
        search(log, "acme", "maria.lopez@example.com");
        search(log, "acme", "call +52 (55) 1234-5678");
        search(log, "acme", "5512345678");
        search(log, "acme", "iphone 15 pro");
        search(log, "acme", "order 2024");
        search(log, "acme", "user@localhost");

        assertEquals(List.of("iphone 15 pro", "order 2024", "user@localhost"),
                log.list("acme").stream().map(NoResultsLog.Entry::term).sorted().toList());
        assertTrue(NoResultsLog.looksPersonal("12 34 56 7"), "separators between digits do not hide a number");
        assertFalse(NoResultsLog.looksPersonal("123 abc 4567"), "letters break the run");
    }

    @Test
    void theSameTermWithinTheRepeatWindowCountsOnce() {
        NoResultsLog log = log();
        log.record("acme", "faq");
        log.record("acme", "faq");                     // the second search box on the same page render
        assertEquals(1, log.list("acme").get(0).count());

        clock.advance(NoResultsLog.REPEAT_WINDOW);
        log.record("acme", "faq");
        assertEquals(2, log.list("acme").get(0).count(), "past the window it is a new search");

        log.record("acme", "other");
        log.record("acme", "faq");                     // a different term in between: counted
        assertEquals(3, log.list("acme").stream().filter(e -> e.term().equals("faq")).findFirst()
                .orElseThrow().count());
    }

    @Test
    void theListIsCappedAndEvictsTheLeastFrequentOldestTerm() {
        NoResultsLog log = log();
        search(log, "acme", "popular");
        search(log, "acme", "popular");
        search(log, "acme", "oldest-single");
        for (int i = 0; i < NoResultsLog.MAX_TERMS - 2; i++) {
            search(log, "acme", "t" + i);
        }
        assertEquals(NoResultsLog.MAX_TERMS, log.list("acme").size());

        search(log, "acme", "newcomer");

        List<String> terms = log.list("acme").stream().map(NoResultsLog.Entry::term).toList();
        assertEquals(NoResultsLog.MAX_TERMS, terms.size(), "never more than the cap");
        assertTrue(terms.contains("newcomer"));
        assertTrue(terms.contains("popular"), "a frequent term survives a flood of one-offs");
        assertFalse(terms.contains("oldest-single"), "the least frequent, longest-unseen term goes first");
        assertEquals("popular", terms.get(0), "listed most frequent first");
    }

    @Test
    void termsUnseenLongerThanTheRetentionDropOff() throws IOException {
        settings.site("acme", Map.of("recordNoResults", true, "noResultsRetentionDays", 10));
        NoResultsLog log = log();
        search(log, "acme", "stale");
        clock.advance(Duration.ofDays(9));
        search(log, "acme", "fresh");
        assertEquals(2, log.list("acme").size());

        clock.advance(Duration.ofDays(2)); // "stale" is now 11 days old, "fresh" 2
        assertEquals(List.of("fresh"), log.list("acme").stream().map(NoResultsLog.Entry::term).toList());

        search(log, "acme", "fresh");
        log.flush();
        String stored = Files.readString(file("acme"), StandardCharsets.UTF_8);
        assertFalse(stored.contains("stale"), "an expired term is not written back");
        assertEquals(10, log.retentionDays("acme"));
    }

    @Test
    void theRetentionSettingIsClampedAndDefaulted() {
        assertEquals(NoResultsLog.DEFAULT_RETENTION_DAYS, log().retentionDays("acme"));
        settings.site("acme", Map.of("noResultsRetentionDays", 100_000));
        assertEquals(NoResultsLog.MAX_RETENTION_DAYS, log().retentionDays("acme"));
        settings.site("acme", Map.of("noResultsRetentionDays", 0));
        assertEquals(1, log().retentionDays("acme"));
    }

    @Test
    void theListSurvivesARestartThroughThePluginsOwnFile() throws IOException {
        NoResultsLog first = log();
        search(first, "acme", "gift cards");
        search(first, "acme", "gift cards");
        search(first, "acme", "returns");
        assertFalse(Files.exists(file("acme")), "nothing touches disk on the search path");
        first.close();

        assertTrue(Files.exists(root.resolve("acme/plugins/search/no-results.tsv")),
                "stored only in the plugin's own per-site subtree — the same file as search 1.x");
        String stored = Files.readString(file("acme"), StandardCharsets.UTF_8);
        assertFalse(stored.matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), "no IP-shaped data in the file");

        NoResultsLog second = log();
        List<NoResultsLog.Entry> list = second.list("acme");
        assertEquals(List.of("gift cards", "returns"), list.stream().map(NoResultsLog.Entry::term).toList());
        assertEquals(2, list.get(0).count());
    }

    @Test
    void clearForgetsTheListInMemoryAndOnDisk() throws IOException {
        NoResultsLog log = log();
        search(log, "acme", "careers");
        log.flush();
        assertTrue(Files.exists(file("acme")));

        log.clear("acme");

        assertTrue(log.list("acme").isEmpty());
        assertFalse(Files.exists(file("acme")));
        log.flush();
        assertFalse(Files.exists(file("acme")), "a clear leaves nothing pending to write back");
        assertTrue(log().list("acme").isEmpty(), "a fresh instance finds nothing either");
    }

    @Test
    void eachSiteHasItsOwnList() {
        NoResultsLog log = log();
        search(log, "acme", "acme-only");
        search(log, "beta", "beta-only");
        assertEquals(List.of("acme-only"), log.list("acme").stream().map(NoResultsLog.Entry::term).toList());
        assertEquals(List.of("beta-only"), log.list("beta").stream().map(NoResultsLog.Entry::term).toList());

        settings.site("beta", Map.of());
        search(log, "beta", "ignored");
        assertEquals(1, log.list("beta").size(), "beta's own opt-out applies to beta only");
    }

    @Test
    void aRestoredSiteIsReadFromItsFilesNotFromStaleMemory() throws IOException {
        NoResultsLog log = log();
        search(log, "acme", "before-restore");
        Files.createDirectories(file("acme").getParent());
        Files.writeString(file("acme"), "from-backup\t7\t" + clock.millis() + "\n", StandardCharsets.UTF_8);

        log.forget("acme");
        log.flush();

        assertEquals(List.of("from-backup"), log.list("acme").stream().map(NoResultsLog.Entry::term).toList());
        assertEquals(7, log.list("acme").get(0).count());
        assertTrue(Files.readString(file("acme"), StandardCharsets.UTF_8).startsWith("from-backup\t7\t"),
                "the forgotten list was never written over the restored file");
    }

    @Test
    void aGarbledFileLoadsWhatItCanAndAnOversizedOneIsIgnored() throws IOException {
        Files.createDirectories(file("acme").getParent());
        long now = clock.millis();
        Files.writeString(file("acme"), String.join("\n",
                "good\t3\t" + now,
                "bad count\tx\t" + now,
                "too\tmany\tfields\there",
                "zero\t0\t" + now,
                "  GOOD \t2\t" + (now - 5),   // re-normalizes into "good" and merges
                "") , StandardCharsets.UTF_8);
        List<NoResultsLog.Entry> list = log().list("acme");
        assertEquals(1, list.size());
        assertEquals("good", list.get(0).term());
        assertEquals(5, list.get(0).count());

        Files.createDirectories(file("beta").getParent());
        Files.writeString(file("beta"), ("t\t1\t" + now + "\n").repeat(50_000), StandardCharsets.UTF_8);
        assertTrue(log().list("beta").isEmpty(), "a file over the size bound is not read");
    }

    @Test
    void aTermNeverReachesALogLine() {
        Logger logger = (Logger) LoggerFactory.getLogger(NoResultsLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PluginSettings broken = new PluginSettings() {
                @Override
                public SettingsView site(String siteId) {
                    throw new IllegalStateException("boom");
                }

                @Override
                public Optional<String> secret(String siteId, String key) {
                    return Optional.empty();
                }
            };
            NoResultsLog log = new NoResultsLog(files, broken, clock, false);
            log.record("acme", "secret-term-xyz"); // must not throw on the public search path
            assertFalse(appender.list.isEmpty(), "the failure is logged");
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("secret-term-xyz")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void anUnreadableFileIsNeverOverwrittenWithAPartialList() throws IOException {
        Files.createDirectories(file("acme").getParent());
        String stored = "precious\t40\t" + clock.millis() + "\n";
        Files.writeString(file("acme"), stored, StandardCharsets.UTF_8);
        boolean[] readable = {false};
        PluginSiteFiles flaky = new DelegatingFiles(files) {
            @Override
            public Optional<String> readText(String siteId, String relPath) throws IOException {
                if (!readable[0]) {
                    throw new IOException("disk hiccup");
                }
                return super.readText(siteId, relPath);
            }
        };
        NoResultsLog log = new NoResultsLog(flaky, settings, clock, false);

        search(log, "acme", "new-term");
        log.flush();
        assertTrue(log.list("acme").isEmpty(), "nothing could be read, and nothing is pretended");
        assertEquals(stored, Files.readString(file("acme"), StandardCharsets.UTF_8),
                "the unreadable file is left exactly as it was");

        readable[0] = true;
        search(log, "acme", "new-term");
        assertEquals(List.of("precious", "new-term"),
                log.list("acme").stream().map(NoResultsLog.Entry::term).toList(),
                "once the file reads again, the stored list is loaded and new searches add to it");
    }

    @Test
    void aMissingSiteFolderMeansAFailedWriteNotACreatedSite() {
        NoResultsLog log = log();
        search(log, "gone", "x");
        assertTrue(log.list("gone").isEmpty(), "a site with no folder has no settings opt-in here");
        settings.site("gone", Map.of("recordNoResults", true));
        search(log, "gone", "late");
        log.flush();
        assertFalse(Files.exists(root.resolve("gone")), "a late flush never creates a deleted site's folder");
    }

    @Test
    void anUnsafeSiteIdIsRefusedBeforeAnyIo() {
        NoResultsLog log = log();
        log.record("../acme", "x"); // the public path never throws
        assertThrows(IllegalArgumentException.class, () -> log.clear("../acme"));
    }

    /** A {@link PluginSiteFiles} that forwards every call; tests override the one they break. */
    private static class DelegatingFiles implements PluginSiteFiles {
        private final PluginSiteFiles d;

        DelegatingFiles(PluginSiteFiles d) {
            this.d = d;
        }

        @Override
        public Optional<String> readText(String siteId, String relPath) throws IOException {
            return d.readText(siteId, relPath);
        }

        @Override
        public void writeText(String siteId, String relPath, String text) throws IOException {
            d.writeText(siteId, relPath, text);
        }

        @Override
        public boolean delete(String siteId, String relPath) throws IOException {
            return d.delete(siteId, relPath);
        }

        @Override
        public boolean deleteTree(String siteId, String relDir) throws IOException {
            return d.deleteTree(siteId, relDir);
        }

        @Override
        public List<FileEntry> list(String siteId, String relDir) throws IOException {
            return d.list(siteId, relDir);
        }

        @Override
        public Optional<FileEntry> stat(String siteId, String relPath) throws IOException {
            return d.stat(siteId, relPath);
        }
    }
}
