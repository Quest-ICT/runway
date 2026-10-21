package mx.quest.runway.log;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The <b>application-realm activity log</b> — an append-only audit trail of operator actions (logins, and every
 * platform mutation: app users, sites, tenant-superadmins, platform settings, plugins, backups, site templates).
 * It is the app-console counterpart to the tenant realm's per-site audit log ({@link SiteLogService}), shown on
 * the Activity → Logs screen.
 *
 * <h2>Isolation &amp; storage</h2>
 * Platform state, not tenant-owned: day-files live under {@code external/activity/} (never inside a tenant
 * folder, so they can never travel in a per-site backup). One line per action, tab-separated
 * ({@code timestamp \t actor \t action \t detail}), with every field stripped of CR/LF/tab/NUL so a crafted
 * value cannot inject a new log line. The timestamp is ISO-8601 with its UTC offset (see {@link AuditJournal}, which
 * owns the line format and the bounded, filtered reader the viewer pages through).
 *
 * <h2>Why synchronous</h2>
 * Unlike the tenant <em>access</em> log (public traffic, high-frequency, hence {@link SiteLogService}'s
 * non-blocking daemon), operator actions are low-frequency and already happen behind an authenticated form POST,
 * so a plain {@code synchronized} append is ample and loses nothing on a full queue (there is none). A write
 * failure is logged and swallowed — auditing must never break the operation it records.
 */
@Service
@EnableConfigurationProperties(AppActivityLogProperties.class)
public class AppActivityLog {

    private static final Logger log = LoggerFactory.getLogger(AppActivityLog.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String DIR = "activity";
    private static final String PREFIX = "activity";
    private static final char SEP = '\t';

    /** The viewer reads only the tail; never load more than this from a day-file (bounds heap regardless of size). */
    private static final long MAX_TAIL_BYTES = 512L * 1024;

    private final StorageLayout layout;
    private final AppActivityLogProperties props;
    private final Object writeLock = new Object();
    private volatile LocalDate lastPrune;

    public AppActivityLog(StorageLayout layout, AppActivityLogProperties props) {
        this.layout = layout;
        this.props = props;
    }

    @PostConstruct
    void pruneOnStartup() {
        if (props.isEnabled()) {
            prune(LocalDate.now(ZONE));
        }
    }

    /**
     * Records one operator action. Synchronous + best-effort: a write failure is logged, never thrown, so
     * auditing can never break the operation being recorded.
     *
     * @param actor  the operator who performed the action (their username), or a system marker
     * @param action a short, stable action key (e.g. {@code appuser.created}, {@code site.deleted})
     * @param detail free-form context (the target id/name, an IP for a login, …); may be empty
     */
    public void record(String actor, String action, String detail) {
        if (!props.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        String line = AuditJournal.formatTime(now, ZONE) + SEP + clean(actor) + SEP + clean(action) + SEP + clean(detail) + '\n';
        String date = DAY.format(now.atZone(ZONE));
        synchronized (writeLock) {
            try {
                Path dir = layout.external().resolve(DIR);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(PREFIX + "-" + date + ".log"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                log.warn("could not record app activity '{}' by '{}': {}", action, actor, e.toString());
            }
            pruneIfDue(LocalDate.now(ZONE));
        }
    }

    /**
     * The matching activity entries in the window {@code [offset, offset + limit)}, newest first, across the whole
     * retention window and within {@link AuditJournal.Limits#DEFAULT} (the result says when the scan was cut
     * short). Never throws for the viewer.
     */
    public AuditPage query(AuditQuery query, int offset, int limit) {
        return AuditJournal.query(layout.external().resolve(DIR), PREFIX, query, offset, limit,
                AuditJournal.Limits.DEFAULT, ZONE);
    }

    /**
     * The most recent {@code maxLines} activity lines, oldest-first. Reads the newest day-files only (bounded),
     * tolerating an unreadable file — the viewer never throws.
     */
    public List<String> tail(int maxLines) {
        Path dir = layout.external().resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> collected = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dayFiles = files
                    .filter(p -> p.getFileName().toString().startsWith(PREFIX + "-"))
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted() // ascending by name == ascending by date
                    .toList();
            for (int i = dayFiles.size() - 1; i >= 0 && collected.size() < maxLines && i >= dayFiles.size() - 3; i--) {
                collected.addAll(0, readTailLines(dayFiles.get(i)));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not tail the app activity log: {}", e.toString());
        }
        int from = Math.max(0, collected.size() - maxLines);
        return List.copyOf(collected.subList(from, collected.size()));
    }

    private static List<String> readTailLines(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= MAX_TAIL_BYTES) {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(size - MAX_TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) MAX_TAIL_BYTES);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill the window
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1); // drop the truncated leading line
            }
            return text.lines().toList();
        }
    }

    private void pruneIfDue(LocalDate today) {
        if (today.equals(lastPrune)) {
            return;
        }
        lastPrune = today;
        prune(today);
    }

    /** Deletes activity day-files older than the retention window (package-visible for tests). */
    void prune(LocalDate today) {
        Path dir = layout.external().resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        LocalDate cutoff = today.minusDays(Math.max(1, props.getRetentionDays()));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                LocalDate fileDate = dateOf(file.getFileName().toString());
                if (fileDate != null && fileDate.isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("app activity log prune failed: {}", e.toString());
        }
    }

    /** Parses the trailing {@code YYYY-MM-DD} from an {@code activity-YYYY-MM-DD.log} filename, or {@code null}. */
    private static LocalDate dateOf(String filename) {
        if (!filename.endsWith(".log") || filename.length() < 14) {
            return null;
        }
        String date = filename.substring(filename.length() - 14, filename.length() - 4);
        try {
            return LocalDate.parse(date, DAY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Strips CR/LF/tab/NUL so a crafted field can never inject a new log line or break the columns. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append((c == '\r' || c == '\n' || c == '\t' || c == 0) ? ' ' : c);
        }
        return out.toString();
    }
}
