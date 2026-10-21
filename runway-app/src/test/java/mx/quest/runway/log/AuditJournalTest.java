package mx.quest.runway.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared audit-journal line format and bounded reader: both time forms parse, the new form keeps the instant
 * unambiguous across a DST fall-back, queries walk newest-first with filters and paging, and every read budget
 * (per-file bytes, total bytes, lines scanned) ends the scan with {@code truncated} rather than reading on.
 */
class AuditJournalTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final AuditJournal.Limits ROOMY = new AuditJournal.Limits(1 << 20, 1 << 24, 1_000_000);

    private static void day(Path dir, String date, String... lines) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("activity-" + date + ".log"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String line(String time, String actor, String action, String detail) {
        return time + "\t" + actor + "\t" + action + "\t" + detail;
    }

    // --- parse ------------------------------------------------------------------------------------

    @Test
    void theNewFormParsesWithItsOwnOffset() {
        AuditEntry e = AuditJournal.parse(line("2026-10-17T08:10:03.123-06:00", "admin", "site.created", "acme"),
                ZoneOffset.UTC).orElseThrow();
        assertTrue(e.offsetKnown());
        assertEquals(ZoneOffset.ofHours(-6), e.time().getOffset(), "the written offset wins, not the legacy zone");
        assertEquals(Instant.parse("2026-10-17T14:10:03.123Z"), e.time().toInstant());
        assertEquals("admin", e.actor());
        assertEquals("site.created", e.action());
        assertEquals("acme", e.detail());
    }

    @Test
    void aUtcLineWrittenAsZParses() {
        String t = AuditJournal.formatTime(Instant.parse("2026-01-02T03:04:05.006Z"), ZoneOffset.UTC);
        assertEquals("2026-01-02T03:04:05.006Z", t);
        assertTrue(AuditJournal.parse(line(t, "a", "b", ""), MX).orElseThrow().offsetKnown());
    }

    @Test
    void theLegacyOffsetLessFormStillParsesInTheLegacyZone() {
        AuditEntry e = AuditJournal.parse(line("2026-08-01 12:00:00", "root", "login.success", "ip=1.2.3.4"), MX)
                .orElseThrow();
        assertFalse(e.offsetKnown(), "a legacy line is flagged: its offset was inferred, not written");
        assertEquals(Instant.parse("2026-08-01T18:00:00Z"), e.time().toInstant(), "read in the legacy zone");
        assertEquals("ip=1.2.3.4", e.detail());
    }

    @Test
    void theOffsetFormKeepsTwoInstantsApartAcrossADstFallBack() {
        // 01:30 happens twice in New York on 2026-11-01. The old format wrote the same text both times.
        ZoneId ny = ZoneId.of("America/New_York");
        Instant first = Instant.parse("2026-11-01T05:30:00Z");  // 01:30 EDT (-04:00)
        Instant second = Instant.parse("2026-11-01T06:30:00Z"); // 01:30 EST (-05:00)
        String a = AuditJournal.formatTime(first, ny);
        String b = AuditJournal.formatTime(second, ny);
        assertNotEquals(a, b, "the offset tells the two wall-clock-identical times apart");
        assertEquals(first, AuditJournal.parse(line(a, "x", "y", ""), MX).orElseThrow().time().toInstant());
        assertEquals(second, AuditJournal.parse(line(b, "x", "y", ""), MX).orElseThrow().time().toInstant());
    }

    @Test
    void missingTrailingFieldsReadAsEmptyAndADetailMayContainSpaces() {
        AuditEntry e = AuditJournal.parse("2026-10-17T08:10:03.123-06:00\tadmin", MX).orElseThrow();
        assertEquals("", e.action());
        assertEquals("", e.detail());
        assertEquals("a b c", AuditJournal.parse(line("2026-10-17T08:10:03.123-06:00", "x", "y", "a b c"), MX)
                .orElseThrow().detail());
    }

    @Test
    void blankAndUnparseableLinesAreSkipped() {
        assertTrue(AuditJournal.parse("", MX).isEmpty());
        assertTrue(AuditJournal.parse(null, MX).isEmpty());
        assertTrue(AuditJournal.parse("not a time\tadmin\tx\ty", MX).isEmpty());
        assertTrue(AuditJournal.parse("2026-13-45 99:99:99\tadmin\tx\ty", MX).isEmpty());
    }

    // --- query ------------------------------------------------------------------------------------

    @Test
    void aMissingDirectoryIsAnEmptyResultNotAnError(@TempDir Path root) {
        AuditPage page = AuditJournal.query(root.resolve("nope"), "activity", AuditQuery.ALL, 0, 10, ROOMY, MX);
        assertTrue(page.rows().isEmpty());
        assertFalse(page.hasMore());
        assertFalse(page.truncated());
    }

    @Test
    void entriesComeNewestFirstAcrossDayFilesAndMixedFormats(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-15", line("2026-10-15 09:00:00", "a", "old.one", "1"),
                line("2026-10-15 10:00:00", "a", "old.two", "2"));
        day(dir, "2026-10-17", line("2026-10-17 07:00:00", "b", "legacy.today", "3"),
                line("2026-10-17T08:00:00.000-06:00", "b", "new.today", "4"));
        List<String> actions = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 10, ROOMY, MX).rows()
                .stream().map(AuditEntry::action).toList();
        assertEquals(List.of("new.today", "legacy.today", "old.two", "old.one"), actions);
    }

    @Test
    void foreignAndMalformedFileNamesAreIgnored(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-17", line("2026-10-17 07:00:00", "b", "kept", ""));
        Files.writeString(dir.resolve("activity-garbage.log"), line("2026-10-17 07:00:00", "x", "nope", "") + "\n");
        Files.writeString(dir.resolve("audit-2026-10-17.log"), line("2026-10-17 07:00:00", "x", "other", "") + "\n");
        Files.writeString(dir.resolve("activity-2026-10-17.log.bak"), line("2026-10-17 07:00:00", "x", "bak", "") + "\n");
        List<String> actions = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 10, ROOMY, MX).rows()
                .stream().map(AuditEntry::action).toList();
        assertEquals(List.of("kept"), actions);
    }

    @Test
    void actorAndActionFilterAreCaseInsensitiveSubstrings(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-17",
                line("2026-10-17 07:00:00", "Alice", "page.saved", "home"),
                line("2026-10-17 07:01:00", "bob", "page.published", "home"),
                line("2026-10-17 07:02:00", "alice", "user.created", "carol"));
        assertEquals(List.of("user.created", "page.saved"), actions(dir, new AuditQuery(null, null, "ALI", null)));
        assertEquals(List.of("page.published", "page.saved"), actions(dir, new AuditQuery(null, null, null, "Page.")));
        assertEquals(List.of("page.saved"), actions(dir, new AuditQuery(null, null, "alice", "page")));
        assertEquals(List.of(), actions(dir, new AuditQuery(null, null, "nobody", null)));
        assertEquals(3, actions(dir, new AuditQuery(null, null, "  ", "")).size(), "blank criteria constrain nothing");
    }

    @Test
    void theDateRangeSelectsDayFilesInclusively(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-14", line("2026-10-14 07:00:00", "a", "d14", ""));
        day(dir, "2026-10-15", line("2026-10-15 07:00:00", "a", "d15", ""));
        day(dir, "2026-10-16", line("2026-10-16 07:00:00", "a", "d16", ""));
        day(dir, "2026-10-17", line("2026-10-17 07:00:00", "a", "d17", ""));
        assertEquals(List.of("d16", "d15"),
                actions(dir, new AuditQuery(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16"), null, null)));
        assertEquals(List.of("d17", "d16"), actions(dir, new AuditQuery(LocalDate.parse("2026-10-16"), null, null, null)));
        assertEquals(List.of("d14"), actions(dir, new AuditQuery(null, LocalDate.parse("2026-10-14"), null, null)));
    }

    @Test
    void pagingWalksTheWholeHistoryWithAnAccurateHasMore(@TempDir Path dir) throws IOException {
        for (int d = 1; d <= 5; d++) {
            String date = "2026-10-0" + d;
            String[] lines = new String[10];
            for (int i = 0; i < 10; i++) {
                lines[i] = line(date + " 10:00:0" + i, "a", "n" + (d * 10 + i), "");
            }
            day(dir, date, lines);
        }
        AuditPage first = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 20, ROOMY, MX);
        assertEquals(20, first.rows().size());
        assertEquals("n59", first.rows().get(0).action(), "the newest entry of the newest day comes first");
        assertTrue(first.hasMore());
        AuditPage last = AuditJournal.query(dir, "activity", AuditQuery.ALL, 40, 20, ROOMY, MX);
        assertEquals(10, last.rows().size());
        assertEquals("n10", last.rows().get(9).action(), "the oldest entry is reachable — not just a tail");
        assertFalse(last.hasMore());
        AuditPage exact = AuditJournal.query(dir, "activity", AuditQuery.ALL, 30, 20, ROOMY, MX);
        assertEquals(20, exact.rows().size());
        assertFalse(exact.hasMore(), "exactly filling the window is not 'more'");
        assertFalse(exact.truncated());
    }

    @Test
    void anOversizedDayFileIsReadOnlyFromItsNewestEndAndFlagged(@TempDir Path dir) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append(line("2026-10-17T08:00:00.000-06:00", "flood", "login.failure", "n" + i)).append('\n');
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("activity-2026-10-17.log"), big.toString());
        day(dir, "2026-10-16", line("2026-10-16 07:00:00", "a", "yesterday", ""));
        AuditJournal.Limits tight = new AuditJournal.Limits(4096, 1 << 20, 1_000_000);
        AuditPage page = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 5000, tight, MX);
        assertTrue(page.truncated(), "the older part of the day was never read — say so");
        assertTrue(page.rows().size() < 2000);
        assertEquals("n1999", page.rows().get(0).detail(), "the newest end is what was read");
        assertTrue(page.rows().stream().noneMatch(e -> e.action().equals("yesterday")),
                "the scan stops at the clipped file rather than skipping past it to older days");
        assertTrue(page.rows().stream().allMatch(e -> e.detail().startsWith("n")),
                "the partial first line of the window is dropped, never parsed as garbage");
    }

    @Test
    void theLineScanBudgetEndsTheSearch(@TempDir Path dir) throws IOException {
        String[] lines = new String[50];
        for (int i = 0; i < 50; i++) {
            lines[i] = line("2026-10-17 10:00:00", i == 0 ? "needle" : "hay", "x", "");
        }
        day(dir, "2026-10-17", lines);
        AuditJournal.Limits few = new AuditJournal.Limits(1 << 20, 1 << 24, 10);
        AuditPage page = AuditJournal.query(dir, "activity", new AuditQuery(null, null, "needle", null), 0, 10, few, MX);
        assertTrue(page.rows().isEmpty(), "the only match lies beyond the budget");
        assertTrue(page.truncated());
        assertEquals(10, page.scanned());
    }

    @Test
    void theTotalByteBudgetEndsTheSearch(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-17", line("2026-10-17 10:00:00", "a", "today", ""));
        day(dir, "2026-10-16", line("2026-10-16 10:00:00", "a", "yesterday", ""));
        long oneFile = Files.size(dir.resolve("activity-2026-10-17.log"));
        AuditJournal.Limits budget = new AuditJournal.Limits(1 << 20, oneFile, 1_000_000);
        AuditPage page = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 10, budget, MX);
        assertEquals(List.of("today"), page.rows().stream().map(AuditEntry::action).toList());
        assertTrue(page.truncated());
    }

    @Test
    void anUnreadableDayFileIsSkippedNotThrown(@TempDir Path dir) throws IOException {
        day(dir, "2026-10-16", line("2026-10-16 10:00:00", "a", "readable", ""));
        Files.createDirectories(dir.resolve("activity-2026-10-17.log")); // a directory where a file should be
        AuditPage page = AuditJournal.query(dir, "activity", AuditQuery.ALL, 0, 10, ROOMY, MX);
        assertEquals(List.of("readable"), page.rows().stream().map(AuditEntry::action).toList());
    }

    private static List<String> actions(Path dir, AuditQuery query) {
        return AuditJournal.query(dir, "activity", query, 0, 100, ROOMY, MX).rows().stream()
                .map(AuditEntry::action).toList();
    }
}
