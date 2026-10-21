package mx.quest.runway.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns a site's own <b>access day-files</b> ({@code sites/<id>/logs/access-YYYY-MM-DD.log}) into two
 * improvement signals for the site manager: the paths that most often answered <b>404</b> (broken links, moved
 * pages, missing assets) and the <b>most-visited pages</b>. It is the platform-owned feedback loop the design
 * promises — no third-party analytics, nothing new is recorded; it only reads what the
 * access log already holds.
 *
 * <h2>What a line counts as</h2>
 * A day-file line is {@code yyyy-MM-dd HH:mm:ss TAB ip TAB method TAB path TAB status} (written by
 * {@link SiteLogService#access}; the path has no query string). A line counts only when its method is
 * {@code GET} and its path is not {@linkplain #isExcluded excluded}. Then:
 * <ul>
 *   <li><b>404</b> — status exactly 404, any path (a missing image is as much a broken link as a missing page).</li>
 *   <li><b>Page view</b> — status 2xx on a {@linkplain #isPageLike page-like} path. The log records no content
 *       type, so "an HTML page" is read off the path: no file extension in the last segment, or {@code .html}/
 *       {@code .htm}, and not under {@code /static/}. A 304 revalidation is not a view.</li>
 * </ul>
 * The client IP is never read into the report, so the aggregate carries no personal data even on a site that
 * keeps full IPs. The log does not record the user agent, so crawlers are counted like people (stated on the
 * screen) — there is no bot filter to offer.
 *
 * <h2>Bounded work</h2>
 * A busy site's logs can be large, and the report runs on an admin's request thread, so every dimension is
 * capped: files are <b>streamed</b> newest-first in fixed buffers (never read whole), a line longer than
 * {@link #MAX_LINE_BYTES} is skipped, scanning stops after {@code maxLines} lines (flagged
 * {@link Report#lineCapReached()} — the newest days are the ones covered), each tally keeps at most
 * {@code maxDistinct} paths (on overflow the least-requested half is dropped and the report is flagged
 * {@link Report#approximate()}), and a stored path is cut at {@link #MAX_KEY_CHARS} characters.
 *
 * <h2>Caching</h2>
 * A report is cached per <b>(site, period, first day, the set of day-files present)</b> for a short TTL
 * ({@link #DEFAULT_TTL}). A new day-file — the next day starting, or retention pruning one away — changes the key,
 * so the cache invalidates itself; the TTL bounds how stale today's still-growing file can look. Concurrent
 * requests for the same key compute it once.
 *
 * <h2>Isolation</h2>
 * The site id is the caller's <b>resolved</b> site (never a request parameter); file paths come from
 * {@link SiteLogService#dayFiles}, composed from that id and {@link LogKind#ACCESS} only.
 */
@Service
public class SiteInsightsService {

    private static final Logger log = LoggerFactory.getLogger(SiteInsightsService.class);

    /** The periods (days) the report offers; each is clamped to the site's effective log retention. */
    public static final List<Integer> PERIODS = List.of(7, 30);
    /** The period used when none (or an unknown one) is requested. */
    public static final int DEFAULT_PERIOD = 7;
    /** Rows shown per table. */
    public static final int TOP_N = 50;
    /** A recorded path longer than this is cut (and marked with an ellipsis) before it becomes a tally key. */
    public static final int MAX_KEY_CHARS = 512;
    /** A log line longer than this many bytes is skipped unread (the writer never produces one for a sane URI). */
    static final int MAX_LINE_BYTES = 8 * 1024;
    /** Default cap on lines scanned per report. */
    static final long DEFAULT_MAX_LINES = 2_000_000L;
    /** Default cap on distinct paths kept per tally. */
    static final int DEFAULT_MAX_DISTINCT = 5_000;
    /** Default report cache lifetime. */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /** Path prefixes that are never public content: the consoles, assets, health, and browser/crawler probes. */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/__", "/app/", "/actuator", "/favicon", "/apple-touch-icon", "/.well-known/");

    /**
     * One row of a table.
     *
     * @param path     the request path as logged (no query string; possibly cut at {@link #MAX_KEY_CHARS})
     * @param count    how many counted requests hit it in the period
     * @param lastSeen the newest line's timestamp ({@code yyyy-MM-dd HH:mm:ss}, server-local time)
     */
    public record PathCount(String path, long count, String lastSeen) {
    }

    /**
     * The report for one site and period.
     *
     * @param days           the effective period in days (requested period clamped to retention)
     * @param from           the first day of the window (inclusive)
     * @param to             the last day of the window (today, inclusive)
     * @param filesScanned   day-files read
     * @param linesScanned   lines read (counted or not)
     * @param pageViews      total counted page views
     * @param notFound       total counted 404s
     * @param topPages       most-visited pages, most first ({@link #TOP_N} at most)
     * @param topNotFound    most-frequent 404 paths, most first ({@link #TOP_N} at most)
     * @param lineCapReached scanning stopped at the line cap, so the oldest days of the window are not included
     * @param approximate    a tally overflowed and dropped rarely-seen paths, so low counts may be undercounted
     * @param loggingEnabled whether the platform captures per-site logs at all ({@code runway.logs.enabled})
     */
    public record Report(int days, LocalDate from, LocalDate to, int filesScanned, long linesScanned,
                         long pageViews, long notFound, List<PathCount> topPages, List<PathCount> topNotFound,
                         boolean lineCapReached, boolean approximate, boolean loggingEnabled) {
    }

    /**
     * A period choice for the screen.
     *
     * @param requested the value sent back as {@code ?days=} (one of {@link #PERIODS})
     * @param effective what it means for this site after the retention clamp
     */
    public record Period(int requested, int effective) {
    }

    private record CacheKey(String siteId, int days, LocalDate from, List<String> files) {
    }

    private final SiteLogService logs;
    private final SiteLogConfigService configs;
    private final LogProperties props;
    private final Clock clock;
    private final long maxLines;
    private final int maxDistinct;
    private final Cache<CacheKey, Report> cache;

    @Autowired
    public SiteInsightsService(SiteLogService logs, SiteLogConfigService configs, LogProperties props) {
        this(logs, configs, props, Clock.system(SiteLogService.zone()), DEFAULT_MAX_LINES, DEFAULT_MAX_DISTINCT,
                DEFAULT_TTL, Ticker.systemTicker());
    }

    /** Test seam: a fixed clock, smaller caps, and a controllable cache ticker. */
    SiteInsightsService(SiteLogService logs, SiteLogConfigService configs, LogProperties props, Clock clock,
                        long maxLines, int maxDistinct, Duration ttl, Ticker ticker) {
        this.logs = logs;
        this.configs = configs;
        this.props = props;
        this.clock = clock;
        this.maxLines = Math.max(1, maxLines);
        this.maxDistinct = Math.max(2, maxDistinct);
        this.cache = Caffeine.newBuilder().maximumSize(256).expireAfterWrite(ttl).ticker(ticker).build();
    }

    /** The site's effective log retention in days (its own preference clamped to the platform cap). */
    public int retentionDays(String siteId) {
        return configs.get(siteId).effectiveRetentionDays(props.getMaxRetentionDays());
    }

    /**
     * The period choices for {@code siteId}: each of {@link #PERIODS} clamped to retention, de-duplicated by the
     * clamped value (a site keeping 5 days of logs is offered one choice, "5 days").
     */
    public List<Period> periods(String siteId) {
        int retention = retentionDays(siteId);
        List<Period> out = new ArrayList<>();
        for (int requested : PERIODS) {
            int effective = Math.min(requested, retention);
            if (out.stream().noneMatch(p -> p.effective() == effective)) {
                out.add(new Period(requested, effective));
            }
        }
        return List.copyOf(out);
    }

    /** {@code requested} when it is one of {@link #PERIODS}, else {@link #DEFAULT_PERIOD} (never trusts input). */
    public static int normalizePeriod(Integer requested) {
        return (requested != null && PERIODS.contains(requested)) ? requested : DEFAULT_PERIOD;
    }

    /**
     * The report for {@code siteId} over the last {@code requestedDays} days (today included), clamped to the
     * site's retention. Never throws for an unreadable file (it is skipped and logged); cached as described in
     * the class doc.
     *
     * @param siteId        the resolved site — callers must pass the bound site, never request input
     * @param requestedDays one of {@link #PERIODS}; anything else is treated as {@link #DEFAULT_PERIOD}
     */
    public Report report(String siteId, int requestedDays) {
        int days = Math.min(normalizePeriod(requestedDays), retentionDays(siteId));
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        if (!props.isEnabled()) {
            return new Report(days, from, to, 0, 0, 0, 0, List.of(), List.of(), false, false, false);
        }
        List<Path> files = logs.dayFiles(siteId, LogKind.ACCESS, from, to);
        List<String> names = files.stream().map(p -> p.getFileName().toString()).toList();
        return cache.get(new CacheKey(siteId, days, from, names), key -> compute(days, from, to, files));
    }

    private Report compute(int days, LocalDate from, LocalDate to, List<Path> files) {
        Tally pages = new Tally(maxDistinct);
        Tally notFound = new Tally(maxDistinct);
        long[] lines = {0};
        boolean capped = false;
        int scanned = 0;
        for (Path file : files) { // newest first: if the cap hits, the recent days are the ones covered
            scanned++;
            boolean more = scan(file, line -> {
                if (lines[0] >= maxLines) {
                    return false;
                }
                lines[0]++;
                classify(line, pages, notFound);
                return true;
            });
            if (!more) {
                capped = true;
                break;
            }
        }
        return new Report(days, from, to, scanned, lines[0], pages.total, notFound.total,
                pages.top(TOP_N), notFound.top(TOP_N), capped, pages.pruned || notFound.pruned, true);
    }

    /** Routes one raw line into the 404 tally, the page-view tally, or nowhere. Malformed lines are ignored. */
    static void classify(String line, Tally pages, Tally notFound) {
        int t1 = line.indexOf('\t');
        int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
        int t3 = t2 < 0 ? -1 : line.indexOf('\t', t2 + 1);
        int t4 = t3 < 0 ? -1 : line.indexOf('\t', t3 + 1);
        if (t4 < 0 || line.indexOf('\t', t4 + 1) >= 0) {
            return; // not exactly five fields
        }
        String timestamp = line.substring(0, t1);
        String method = line.substring(t2 + 1, t3);
        String path = line.substring(t3 + 1, t4);
        String statusText = line.substring(t4 + 1).strip();
        if (!"GET".equals(method) || path.isEmpty() || path.charAt(0) != '/' || isExcluded(path)) {
            return;
        }
        int status;
        try {
            status = Integer.parseInt(statusText);
        } catch (NumberFormatException e) {
            return;
        }
        if (status == 404) {
            notFound.add(capKey(path), timestamp);
        } else if (status >= 200 && status < 300 && isPageLike(path)) {
            pages.add(capKey(path), timestamp);
        }
    }

    /** Whether {@code path} is never public content (consoles, assets, health, browser icon/well-known probes). */
    static boolean isExcluded(String path) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a 2xx on {@code path} reads as an HTML page view (see the class doc for the heuristic). */
    static boolean isPageLike(String path) {
        if (path.startsWith("/static/")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.equals("html") || ext.equals("htm");
    }

    /** Cuts an over-long path to {@link #MAX_KEY_CHARS} (never splitting a surrogate pair) and marks the cut. */
    static String capKey(String path) {
        if (path.length() <= MAX_KEY_CHARS) {
            return path;
        }
        int end = MAX_KEY_CHARS;
        if (Character.isHighSurrogate(path.charAt(end - 1))) {
            end--;
        }
        return path.substring(0, end) + "…";
    }

    /** Receives one decoded line; returns {@code false} to stop the scan. */
    @FunctionalInterface
    interface LineSink {
        boolean accept(String line);
    }

    /**
     * Streams {@code file} line by line in a fixed buffer. A line over {@link #MAX_LINE_BYTES} is skipped, and a
     * final line without its newline (the writer may be mid-append on today's file) is left for the next report.
     *
     * @return {@code false} when the sink stopped the scan; {@code true} when the file was read to its end (or
     *         could not be read, which is logged and treated as empty)
     */
    static boolean scan(Path file, LineSink sink) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            byte[] line = new byte[MAX_LINE_BYTES];
            int length = 0;
            boolean overflow = false;
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        if (!overflow && length > 0
                                && !sink.accept(new String(line, 0, length, StandardCharsets.UTF_8))) {
                            return false;
                        }
                        length = 0;
                        overflow = false;
                    } else if (!overflow) {
                        if (length < line.length) {
                            line[length++] = b;
                        } else {
                            overflow = true;
                        }
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("site insights skipped unreadable log {}: {}", file.getFileName(), e.toString());
            return true;
        }
    }

    /**
     * A bounded path → (count, last seen) tally. When full, admitting a new path first drops the
     * least-requested half (oldest first among equals) and marks the tally {@link #pruned}: memory stays bounded
     * under a flood of unique paths, and the paths that matter — the frequent ones — survive.
     */
    static final class Tally {

        private static final class Entry {
            long count;
            String lastSeen = "";
        }

        private final int capacity;
        private final Map<String, Entry> entries = new HashMap<>();
        long total;
        boolean pruned;

        Tally(int capacity) {
            this.capacity = capacity;
        }

        void add(String key, String timestamp) {
            total++;
            Entry entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= capacity) {
                    prune();
                }
                entry = new Entry();
                entries.put(key, entry);
            }
            entry.count++;
            if (timestamp.compareTo(entry.lastSeen) > 0) {
                entry.lastSeen = timestamp; // yyyy-MM-dd HH:mm:ss sorts chronologically as text
            }
        }

        int size() {
            return entries.size();
        }

        private void prune() {
            List<Map.Entry<String, Entry>> ordered = new ArrayList<>(entries.entrySet());
            ordered.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().count)
                    .thenComparing(e -> e.getValue().lastSeen));
            int drop = Math.max(1, ordered.size() / 2);
            for (int i = 0; i < drop; i++) {
                entries.remove(ordered.get(i).getKey());
            }
            pruned = true;
        }

        /** The {@code n} most-counted paths: count desc, then most recently seen, then path. */
        List<PathCount> top(int n) {
            return entries.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().count).reversed()
                            .thenComparing((Map.Entry<String, Entry> e) -> e.getValue().lastSeen,
                                    Comparator.reverseOrder())
                            .thenComparing(Map.Entry::getKey))
                    .limit(n)
                    .map(e -> new PathCount(e.getKey(), e.getValue().count, e.getValue().lastSeen))
                    .toList();
        }
    }
}
