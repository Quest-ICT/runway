package mx.quest.runway.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared <b>line format and reader</b> of Runway's two audit journals: the application activity log
 * ({@code external/activity/activity-YYYY-MM-DD.log}) and each site's audit log
 * ({@code sites/<id>/logs/audit-YYYY-MM-DD.log}). Both write one line per action,
 * {@code time \t actor \t action \t detail}, into a file per local day.
 *
 * <h2>Time format</h2>
 * Lines are written as ISO-8601 local date-time <b>with its UTC offset</b> and milliseconds
 * ({@code 2026-10-17T08:10:03.123-06:00}), so an entry's instant is unambiguous across a DST change or a server
 * time-zone move. Lines written before 0.5.0-beta14 carry {@code yyyy-MM-dd HH:mm:ss} with no offset; the reader
 * still accepts them and interprets them in the server's current zone, flagging {@link AuditEntry#offsetKnown()}
 * {@code false}. Both forms coexist in one file across the upgrade day.
 *
 * <h2>Bounded reads</h2>
 * A query walks the day-files <b>newest first</b> (so the first page needs the least I/O) and never reads more
 * than {@link Limits}: a byte cap per file (the newest end of an oversized file is read — a login-failure flood
 * can bloat one day), a byte budget per request, and a cap on lines examined. Reaching any of them ends the scan
 * with {@link AuditPage#truncated()} set, so the screen can say so rather than silently showing a partial
 * history as the whole. Unparseable lines are skipped (counted as scanned), never thrown.
 *
 * <p>Thread-safety: stateless; safe to call concurrently with the writers (a line being appended is either seen
 * whole or not at all, and a partial last line simply fails to parse).
 */
public final class AuditJournal {

    private static final Logger log = LoggerFactory.getLogger(AuditJournal.class);

    /** The on-disk time format for new lines: local date-time, milliseconds, and the offset ({@code Z} at UTC). */
    static final DateTimeFormatter WRITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    /** The pre-beta14 offset-less form, still accepted on read. */
    private static final DateTimeFormatter LEGACY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * The read budget of one query.
     *
     * @param maxBytesPerFile the most read from any one day-file (its newest end)
     * @param maxTotalBytes   the most read across all day-files for one query
     * @param maxLinesScanned the most lines examined for one query
     */
    public record Limits(long maxBytesPerFile, long maxTotalBytes, long maxLinesScanned) {

        /** The viewer's defaults: 4 MiB per file, 32 MiB and 250 000 lines per request. */
        public static final Limits DEFAULT = new Limits(4L * 1024 * 1024, 32L * 1024 * 1024, 250_000);
    }

    private AuditJournal() {
    }

    /** Formats {@code when} in {@code zone} as a new journal line's time field. */
    static String formatTime(Instant when, ZoneId zone) {
        return WRITE_TIME.format(when.atZone(zone));
    }

    /**
     * Parses one journal line. Accepts both time forms (see the class doc); a legacy time is placed in
     * {@code legacyZone}. Missing trailing fields read as empty.
     *
     * @return the entry, or empty for a blank line or one whose time field does not parse
     */
    public static Optional<AuditEntry> parse(String line, ZoneId legacyZone) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] parts = line.split("\t", 4);
        String rawTime = parts[0].strip();
        OffsetDateTime time;
        boolean offsetKnown;
        try {
            time = OffsetDateTime.parse(rawTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            offsetKnown = true;
        } catch (DateTimeParseException notOffset) {
            try {
                LocalDateTime local = LocalDateTime.parse(rawTime, LEGACY_TIME);
                time = local.atZone(legacyZone).toOffsetDateTime();
                offsetKnown = false;
            } catch (DateTimeParseException notLegacy) {
                return Optional.empty();
            }
        }
        return Optional.of(new AuditEntry(time, offsetKnown, field(parts, 1), field(parts, 2), field(parts, 3)));
    }

    private static String field(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    /**
     * Returns the window {@code [offset, offset + limit)} of the entries in {@code dir}'s {@code <prefix>-*.log}
     * day-files that match {@code query}, newest first, within {@code limits}.
     *
     * @param dir        the journal directory (absent = empty result)
     * @param prefix     the day-file prefix ({@code activity} / {@code audit})
     * @param query      the filter
     * @param offset     how many matches to skip (≥ 0)
     * @param limit      the most matches to return (≥ 1)
     * @param limits     the read budget
     * @param legacyZone the zone a legacy offset-less line is read in
     * @return the page; never {@code null}, never throws for an unreadable file (logged, skipped)
     */
    public static AuditPage query(Path dir, String prefix, AuditQuery query, int offset, int limit, Limits limits,
                                  ZoneId legacyZone) {
        int skip = Math.max(0, offset);
        int want = Math.max(1, limit);
        if (dir == null || !Files.isDirectory(dir)) {
            return AuditPage.empty(skip);
        }
        List<Path> dayFiles = dayFilesNewestFirst(dir, prefix, query);
        List<AuditEntry> rows = new ArrayList<>(Math.min(want, 512));
        int matched = 0;
        long scanned = 0;
        long bytes = 0;
        boolean truncated = false;
        boolean hasMore = false;
        files:
        for (Path file : dayFiles) {
            long remaining = limits.maxTotalBytes() - bytes;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            Chunk chunk;
            try {
                chunk = readNewestEnd(file, Math.min(limits.maxBytesPerFile(), remaining));
            } catch (IOException | RuntimeException e) {
                log.warn("could not read audit journal file {}: {}", file, e.toString());
                continue;
            }
            bytes += chunk.bytesRead();
            List<String> lines = chunk.lines();
            for (int i = lines.size() - 1; i >= 0; i--) { // newest line last in the file → walk backwards
                if (scanned >= limits.maxLinesScanned()) {
                    truncated = true;
                    break files;
                }
                scanned++;
                Optional<AuditEntry> parsed = parse(lines.get(i), legacyZone);
                if (parsed.isEmpty() || !query.matches(parsed.get())) {
                    continue;
                }
                if (matched >= skip + want) {
                    hasMore = true; // one beyond the window proves there is a next page
                    break files;
                }
                if (matched >= skip) {
                    rows.add(parsed.get());
                }
                matched++;
            }
            if (chunk.clipped()) {
                truncated = true; // the older part of this day was never read
                break;
            }
        }
        return new AuditPage(rows, skip, hasMore, truncated, scanned);
    }

    /** The {@code <prefix>-YYYY-MM-DD.log} files in range, newest first (ascending name == ascending date). */
    private static List<Path> dayFilesNewestFirst(Path dir, String prefix, AuditQuery query) {
        String head = prefix + "-";
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (!name.startsWith(head) || !name.endsWith(".log")) {
                            return false;
                        }
                        LocalDate day = dayOf(name.substring(head.length(), name.length() - 4));
                        return day != null && query.coversDay(day);
                    })
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.warn("could not list audit journal {}: {}", dir, e.toString());
            return List.of();
        }
    }

    private static LocalDate dayOf(String date) {
        try {
            return LocalDate.parse(date, DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A file's lines as read, how many bytes that cost, and whether its older start was left unread. */
    private record Chunk(List<String> lines, long bytesRead, boolean clipped) {
    }

    /**
     * Reads a day-file whole when it fits {@code cap}, else only its newest {@code cap} bytes (dropping the
     * partial first line) — so one bloated day can never pull an unbounded amount into the heap.
     */
    private static Chunk readNewestEnd(Path file, long cap) throws IOException {
        long size = Files.size(file);
        if (size <= cap) {
            return new Chunk(Files.readAllLines(file, StandardCharsets.UTF_8), size, false);
        }
        int window = (int) Math.max(0, Math.min(cap, Integer.MAX_VALUE - 16));
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(size - window);
            ByteBuffer buffer = ByteBuffer.allocate(window);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill the window
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            int firstNewline = text.indexOf('\n');
            text = firstNewline >= 0 ? text.substring(firstNewline + 1) : "";
            return new Chunk(text.lines().toList(), window, true);
        }
    }
}
