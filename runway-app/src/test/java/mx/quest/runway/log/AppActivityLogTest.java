package mx.quest.runway.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application-realm activity log: append + tail preserve order, a crafted field cannot inject a new line,
 * disabling silences it, and old day-files are pruned past the retention window.
 */
class AppActivityLogTest {

    private static StorageLayout layout(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return StorageLayout.from(props);
    }

    private static AppActivityLog log(Path root, boolean enabled, int retentionDays) {
        AppActivityLogProperties props = new AppActivityLogProperties();
        props.setEnabled(enabled);
        props.setRetentionDays(retentionDays);
        return new AppActivityLog(layout(root), props);
    }

    @Test
    void recordsAndTailsInOrder(@TempDir Path root) {
        AppActivityLog log = log(root, true, 90);
        log.record("admin", "site.created", "acme");
        log.record("admin", "appuser.created", "bob (SYSTEMOPERATOR)");
        log.record("op", "platform.settings-changed", "");

        List<String> lines = log.tail(10);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("admin") && lines.get(0).contains("site.created")
                && lines.get(0).contains("acme"));
        assertTrue(lines.get(2).contains("platform.settings-changed"), "newest is last");
    }

    @Test
    void aCraftedFieldCannotInjectALine(@TempDir Path root) {
        AppActivityLog log = log(root, true, 90);
        log.record("evil\nadmin", "x\naction", "detail\nsecond line");
        // Despite the embedded newlines, exactly ONE line is stored (CR/LF stripped to spaces).
        assertEquals(1, log.tail(10).size(), "embedded newlines are neutralized — no log injection");
    }

    @Test
    void disabledLogRecordsNothing(@TempDir Path root) {
        AppActivityLog log = log(root, false, 90);
        log.record("admin", "site.created", "acme");
        assertTrue(log.tail(10).isEmpty(), "a disabled activity log stores nothing");
    }

    @Test
    void tailIsBounded(@TempDir Path root) {
        AppActivityLog log = log(root, true, 90);
        for (int i = 0; i < 50; i++) {
            log.record("admin", "site.created", "s" + i);
        }
        List<String> lines = log.tail(10);
        assertEquals(10, lines.size(), "tail returns at most the requested number of lines");
        assertTrue(lines.get(9).contains("s49"), "the newest line is last");
    }

    @Test
    void aNewLineCarriesItsUtcOffset(@TempDir Path root) {
        AppActivityLog log = log(root, true, 90);
        log.record("admin", "site.created", "acme");
        String line = log.tail(1).get(0);
        assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})\\tadmin\\t.*"),
                "ISO-8601 with milliseconds and an offset: " + line);
    }

    @Test
    void queryReadsBackParsedEntriesNewestFirstIncludingLegacyLines(@TempDir Path root) throws IOException {
        Path dir = root.resolve("external").resolve("activity");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("activity-" + LocalDate.now().minusDays(1) + ".log"),
                LocalDate.now().minusDays(1) + " 09:00:00\tadmin\tsite.created\tlegacy\n");
        AppActivityLog log = log(root, true, 90);
        log.record("admin", "site.deleted", "fresh");

        AuditPage page = log.query(AuditQuery.ALL, 0, 10);
        assertEquals(2, page.rows().size());
        assertEquals("site.deleted", page.rows().get(0).action());
        assertTrue(page.rows().get(0).offsetKnown());
        assertEquals("legacy", page.rows().get(1).detail());
        assertFalse(page.rows().get(1).offsetKnown(), "the pre-upgrade line still reads, flagged offset-less");
    }

    @Test
    void pruneDeletesDayFilesPastRetention(@TempDir Path root) throws IOException {
        AppActivityLog log = log(root, true, 30);
        Path dir = root.resolve("external").resolve("activity");
        Files.createDirectories(dir);
        Path old = dir.resolve("activity-2000-01-01.log");
        Path recent = dir.resolve("activity-" + LocalDate.now() + ".log");
        Files.writeString(old, "2000-01-01 00:00:00\tadmin\tsite.created\told\n");
        Files.writeString(recent, LocalDate.now() + " 00:00:00\tadmin\tsite.created\tnew\n");

        log.prune(LocalDate.now());

        assertFalse(Files.exists(old), "a day-file older than retention is pruned");
        assertTrue(Files.exists(recent), "a current day-file is kept");
    }
}
