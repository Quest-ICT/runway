package mx.quest.runway.log;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Captures a site's access and audit log lines <b>asynchronously</b>: producers ({@link #access}/{@link #audit})
 * only enqueue a pre-cleaned event on a bounded queue and return immediately — a full queue drops the event and
 * bumps a counter rather than ever blocking a request thread. One daemon thread drains the queue, writes each
 * line to a per-day file under {@code sites/<id>/logs/}, anonymizes client IPs at write time when the site asks,
 * and prunes files past the site's (capped) retention once a day.
 *
 * <h2>Isolation</h2>
 * Logs are strictly tenant-private: they live inside the site folder, are keyed only by a site id (never a
 * request), and there is no app-realm surface that reads them. The day-file name is composed from a
 * {@link LogKind} and a formatted date — never from user input — and the site id is path-safe.
 *
 * <h2>Durability</h2>
 * Best-effort: on a hard kill, at most the in-flight queue (bounded) is lost; a clean shutdown flushes it.
 */
@Service
public class SiteLogService {

    private static final Logger log = LoggerFactory.getLogger(SiteLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final char SEP = '\t';

    /** The viewer only reads the tail; never load more than this from a day-file (bounds heap regardless of file size). */
    private static final long MAX_TAIL_BYTES = 512L * 1024;

    /** One captured line, with fields already stripped of CR/LF (no log injection past this point). */
    private record LogEvent(String siteId, LogKind kind, long epochMillis, List<String> fields) {
    }

    private final StorageLayout layout;
    private final SiteLogConfigService configs;
    private final LogProperties props;
    private final BlockingQueue<LogEvent> queue;
    /**
     * Queue slots only audit events may take. Access and audit lines share the queue, and a flood that gets past the
     * protection edge (a credential-stuffing run, two events per attempt) would otherwise drop audit lines, the
     * {@code login.failure} records that are the evidence of that very attack, as often as access lines. With an
     * eighth of the queue held back, access lines are dropped first.
     */
    private final int auditReserve;
    static final int AUDIT_RESERVE_DIVISOR = 8;
    private final AtomicLong dropped = new AtomicLong();
    private static final long DROP_WARN_INTERVAL_MS = 60_000L;
    private volatile long lastDropWarnAt;
    private volatile boolean running;
    private volatile LocalDate lastPrune;
    private Thread worker;

    public SiteLogService(StorageLayout layout, SiteLogConfigService configs, LogProperties props) {
        this.layout = layout;
        this.configs = configs;
        this.props = props;
        int capacity = Math.max(16, props.getQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.auditReserve = capacity / AUDIT_RESERVE_DIVISOR;
    }

    @PostConstruct
    void start() {
        if (!props.isEnabled()) {
            return;
        }
        running = true;
        worker = new Thread(this::drainLoop, "runway-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
    }

    /** Records one public request. Non-blocking; a full queue drops the line (counted). */
    public void access(String siteId, String ip, String method, String path, int status) {
        offer(new LogEvent(siteId, LogKind.ACCESS, System.currentTimeMillis(),
                List.of(clean(ip), clean(method), clean(path), Integer.toString(status))));
    }

    /** Records one security-relevant action. Non-blocking; a full queue drops the line (counted). */
    public void audit(String siteId, String actor, String action, String detail) {
        offer(new LogEvent(siteId, LogKind.AUDIT, System.currentTimeMillis(),
                List.of(clean(actor), clean(action), clean(detail))));
    }

    /** Accepts events without starting the writer, so a test can fill the queue. */
    void acceptWithoutWriting() {
        running = true;
    }

    /** Events waiting to be written (tests). */
    int queued() {
        return queue.size();
    }

    /** The number of events dropped because the queue was full (best-effort observability). */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * The most recent {@code maxLines} log lines of {@code kind} for {@code siteId}, oldest-first. Reads the
     * newest day-files only (bounded), tolerating an unreadable file. Never throws for the viewer.
     */
    public List<String> tail(String siteId, LogKind kind, int maxLines) {
        Path dir = logsDir(siteId);
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> collected = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dayFiles = files
                    .filter(p -> p.getFileName().toString().startsWith(kind.prefix() + "-"))
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted() // ascending by name == ascending by date
                    .toList();
            // Read from the newest backwards until we have enough lines (cap the file lookback).
            for (int i = dayFiles.size() - 1; i >= 0 && collected.size() < maxLines && i >= dayFiles.size() - 3; i--) {
                collected.addAll(0, readTailLines(dayFiles.get(i)));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not tail {} log for '{}': {}", kind, siteId, e.toString());
        }
        int from = Math.max(0, collected.size() - maxLines);
        return List.copyOf(collected.subList(from, collected.size()));
    }

    /** The zone day-files are dated in: a line lands in the file named for the local date it was written. */
    public static ZoneId zone() {
        return ZONE;
    }

    /**
     * The existing {@code kind} day-files of {@code siteId} dated within {@code [from, to]} (both inclusive),
     * <b>newest first</b>. Only regular files whose name parses as {@code <kind>-YYYY-MM-DD.log} are returned; the
     * path is composed from the site id and the kind, never from request input. Empty (never throws) when the
     * site has no logs, the id is unsafe, or the folder cannot be listed. Readers stream these files — this method
     * never opens them.
     */
    public List<Path> dayFiles(String siteId, LogKind kind, LocalDate from, LocalDate to) {
        Path dir = logsDir(siteId);
        if (dir == null || !Files.isDirectory(dir) || from == null || to == null) {
            return List.of();
        }
        String prefix = kind.prefix() + "-";
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> p.getFileName().toString().length() == prefix.length() + 14) // YYYY-MM-DD.log
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {
                        LocalDate date = dateOf(p.getFileName().toString());
                        return date != null && !date.isBefore(from) && !date.isAfter(to);
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.warn("could not list {} day-files for '{}': {}", kind, siteId, e.toString());
            return List.of();
        }
    }

    /**
     * The matching entries of {@code siteId}'s <b>audit</b> log in the window {@code [offset, offset + limit)},
     * newest first, across the site's whole retention window and within {@link AuditJournal.Limits#DEFAULT}.
     * Reads only that site's own folder (an unsafe id reads nothing). Never throws for the viewer.
     */
    public AuditPage queryAudit(String siteId, AuditQuery query, int offset, int limit) {
        return AuditJournal.query(logsDir(siteId), LogKind.AUDIT.prefix(), query, offset, limit,
                AuditJournal.Limits.DEFAULT, ZONE);
    }

    /**
     * Reads a day-file's lines, but never more than {@link #MAX_TAIL_BYTES} from its end — so an access log
     * bloated by a traffic flood can never be pulled whole into the heap when an admin opens the viewer.
     * When the window is truncated the (possibly partial) first line is dropped.
     */
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

    private void offer(LogEvent event) {
        if (!running) {
            return;
        }
        boolean accepted = event.kind() == LogKind.ACCESS && queue.remainingCapacity() <= auditReserve
                ? false : queue.offer(event);
        if (!accepted) {
            long total = dropped.incrementAndGet();
            // A saturated queue silently losing events (audit lines included) is exactly what an operator needs
            // told — but the drop path must stay non-blocking and un-spammy, so warn at most once a minute.
            long now = System.currentTimeMillis();
            if (now - lastDropWarnAt >= DROP_WARN_INTERVAL_MS) {
                lastDropWarnAt = now;
                log.warn("site log queue saturated — {} event(s) dropped so far; raise runway.logs.queue-capacity "
                        + "if this persists", total);
            }
        }
    }

    private void drainLoop() {
        List<LogEvent> batch = new ArrayList<>(256);
        while (running || !queue.isEmpty()) {
            try {
                LogEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 511);
                    writeBatch(batch);
                    batch.clear();
                }
                pruneIfDue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running) {
                    break;
                }
            } catch (RuntimeException e) {
                log.warn("log writer batch failed: {}", e.toString());
                batch.clear();
            }
        }
    }

    private void flushRemaining() {
        List<LogEvent> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    /** Appends each event to its day-file; groups nothing fancy — one small append per event, batched by the drain. */
    private void writeBatch(List<LogEvent> batch) {
        for (LogEvent event : batch) {
            try {
                appendLine(event);
            } catch (IOException | RuntimeException e) {
                log.warn("dropping a log line for '{}': {}", event.siteId(), e.toString());
            }
        }
    }

    private void appendLine(LogEvent event) throws IOException {
        Path dir = logsDir(event.siteId());
        if (dir == null) {
            return; // unsafe site id — never happens for a real site, but fail closed
        }
        Instant when = Instant.ofEpochMilli(event.epochMillis());
        String date = DAY.format(when.atZone(ZONE));
        Path file = dir.resolve(event.kind().prefix() + "-" + date + ".log");
        Files.createDirectories(dir);

        List<String> fields = new ArrayList<>(event.fields());
        if (event.kind() == LogKind.ACCESS && !fields.isEmpty() && configs.get(event.siteId()).anonymize()) {
            fields.set(0, maskIp(fields.get(0)));
        }
        // The audit journal carries its UTC offset (unambiguous across DST / a zone move — AuditJournal owns that
        // format and reads both it and the older offset-less form). The access log keeps its established
        // offset-less shape: it is the high-volume stream operators may already parse, and nothing in Runway
        // orders access lines across a DST change.
        String time = event.kind() == LogKind.AUDIT
                ? AuditJournal.formatTime(when, ZONE)
                : TS.format(when.atZone(ZONE));
        String line = time + SEP + String.join(String.valueOf(SEP), fields) + '\n';
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void pruneIfDue() {
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(lastPrune)) {
            return;
        }
        lastPrune = today;
        pruneAll(today);
    }

    /** Prunes every site's over-retention day-files as of {@code today} (package-visible for tests). */
    void pruneAll(LocalDate today) {
        Path sites = layout.sites();
        if (!Files.isDirectory(sites)) {
            return;
        }
        try (Stream<Path> siteDirs = Files.list(sites)) {
            siteDirs.filter(Files::isDirectory).forEach(siteDir -> pruneSite(siteDir, today));
        } catch (IOException e) {
            log.warn("log prune scan failed: {}", e.toString());
        }
    }

    private void pruneSite(Path siteDir, LocalDate today) {
        Path dir = siteDir.resolve("logs");
        if (!Files.isDirectory(dir)) {
            return;
        }
        int retention = configs.get(siteDir.getFileName().toString()).effectiveRetentionDays(props.getMaxRetentionDays());
        LocalDate cutoff = today.minusDays(retention);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                LocalDate fileDate = dateOf(file.getFileName().toString());
                if (fileDate != null && fileDate.isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("log prune failed for {}: {}", dir, e.toString());
        }
    }

    /** Parses the trailing {@code YYYY-MM-DD} from a {@code <kind>-YYYY-MM-DD.log} filename, or {@code null}. */
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

    private Path logsDir(String siteId) {
        try {
            return layout.siteDir(siteId).resolve("logs");
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
    }

    /** Strips CR/LF (and NUL) so a crafted field can never inject a new log line. */
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

    /** Masks the last component of an IP so a stored line carries no full address when anonymization is on. */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        if (ip.indexOf('.') >= 0) { // IPv4
            int last = ip.lastIndexOf('.');
            return ip.substring(0, last) + ".0";
        }
        if (ip.indexOf(':') >= 0) { // IPv6 — keep the first three hextets
            String[] parts = ip.split(":");
            return String.join(":", parts.length >= 3 ? new String[] {parts[0], parts[1], parts[2]} : parts) + "::";
        }
        return "0.0.0.0";
    }
}
