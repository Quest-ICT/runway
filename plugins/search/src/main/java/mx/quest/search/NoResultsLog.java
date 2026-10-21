package mx.quest.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers, per site, the searches that found <b>nothing</b> — a content-gap signal for the site manager (what
 * visitors look for that the site does not have). Each entry is only a <b>normalized term, a count, and the last
 * time it was seen</b>: no IP, no user, no session, no full query string, no timestamp per search.
 *
 * <h2>Off by default</h2>
 * A search term is free text a visitor typed, so it can carry personal data (a name, an email, a phone number).
 * Recording is therefore opt-in per site ({@code recordNoResults}, default {@code false}); when it is off,
 * {@link #record} does nothing at all. Even when on, a term that <b>looks like</b> an email address or a phone /
 * ID number (seven or more digits once spaces, dashes, dots, parentheses and a leading plus are ignored) is
 * dropped unrecorded. The term never reaches an application log line.
 *
 * <h2>Bounded</h2>
 * <ul>
 *   <li><b>Term length</b> — after normalization (Unicode NFC, trimmed, lower-cased, whitespace runs collapsed to
 *       one space, control characters removed) a term is cut to {@link #MAX_TERM_CHARS} characters.</li>
 *   <li><b>Term count</b> — at most {@link #MAX_TERMS} per site. Admitting a new term to a full list evicts the
 *       least frequent one (the longest-unseen among equals), so a flood of one-off junk queries churns only the
 *       bottom of the list and the recurring gaps stay.</li>
 *   <li><b>Retention</b> — a term not seen for {@code noResultsRetentionDays} (default
 *       {@link #DEFAULT_RETENTION_DAYS}) is dropped from listings and from the next write.</li>
 *   <li><b>Repeats</b> — the same term on the same site within {@link #REPEAT_WINDOW} counts once, because a page
 *       carrying two search boxes renders (and would record) the same empty result twice per request.</li>
 * </ul>
 *
 * <h2>Storage</h2>
 * In memory per site, loaded lazily from the plugin's own per-site file {@value #FILE} in its
 * {@link PluginSiteFiles} folder ({@code sites/<id>/plugins/search/no-results.tsv}: inside the tenant folder, so a
 * full backup carries it; declared {@code siteData.personal} in {@code plugin.yml}, so a <b>transfer copy leaves it
 * out</b>), and written back by a background flush every {@link #FLUSH_INTERVAL} when something changed, and on
 * shutdown — never synchronously on the public search path, so a burst of searches costs no disk I/O per request. A
 * crash loses at most the last interval. A wholesale site replacement (restore / delete) drops the in-memory copy
 * unwritten ({@link #forget}), so the restored file is what is read next. {@link #clear} empties both at once. A file
 * that cannot be read is not replaced: the site's list stays unloaded (nothing is recorded for it, and nothing is
 * written over the file) until a later read succeeds.
 *
 * <p>Thread-safe: each site's list is guarded by its own monitor.
 */
public class NoResultsLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NoResultsLog.class);

    /** The per-site setting that turns recording on (default off). */
    static final String ENABLED_KEY = "recordNoResults";
    /** The per-site setting for how long an unseen term is kept. */
    static final String RETENTION_KEY = "noResultsRetentionDays";
    static final int DEFAULT_RETENTION_DAYS = 90;
    static final int MAX_RETENTION_DAYS = 365;
    static final int MAX_TERMS = 500;
    static final int MAX_TERM_CHARS = 100;
    static final Duration REPEAT_WINDOW = Duration.ofSeconds(1);
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(30);
    /** The per-site file, relative to this plugin's own {@link PluginSiteFiles} folder. */
    static final String FILE = "no-results.tsv";
    /** Upper bound on the file, read or written (500 terms of at most 100 chars + numbers fits well inside). */
    static final long MAX_FILE_BYTES = 512L * 1024;

    /**
     * One recorded term.
     *
     * @param term     the normalized term
     * @param count    how many no-result searches used it
     * @param lastSeen when it was last searched
     */
    public record Entry(String term, long count, Instant lastSeen) {
    }

    /** One site's in-memory list plus its write state; guarded by its own monitor. */
    private static final class SiteTerms {
        final Map<String, MutableEntry> terms = new HashMap<>();
        boolean dirty;
        String lastTerm;
        long lastAtMillis;
    }

    private static final class MutableEntry {
        long count;
        long lastSeenMillis;

        MutableEntry(long count, long lastSeenMillis) {
            this.count = count;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private final PluginSiteFiles files;
    private final PluginSettings settings;
    private final Clock clock;
    private final ConcurrentMap<String, SiteTerms> sites = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher;

    /** Production wiring: system clock and a daemon thread flushing every {@link #FLUSH_INTERVAL}. */
    public NoResultsLog(PluginSiteFiles files, PluginSettings settings) {
        this(files, settings, Clock.systemUTC(), true);
    }

    /** Test seam: a controllable clock, and no background thread (tests call {@link #flush()} themselves). */
    NoResultsLog(PluginSiteFiles files, PluginSettings settings, Clock clock, boolean background) {
        this.files = files;
        this.settings = settings;
        this.clock = clock;
        if (background) {
            this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "search-no-results-flush");
                t.setDaemon(true);
                return t;
            });
            long period = FLUSH_INTERVAL.toMillis();
            flusher.scheduleWithFixedDelay(this::flushQuietly, period, period, TimeUnit.MILLISECONDS);
        } else {
            this.flusher = null;
        }
    }

    /** Whether recording is turned on for {@code siteId} (the per-site {@code recordNoResults}, default off). */
    public boolean enabled(String siteId) {
        return settings.site(siteId).bool(ENABLED_KEY, false);
    }

    /** The site's retention in days ({@code noResultsRetentionDays}, clamped to {@code 1..365}). */
    public int retentionDays(String siteId) {
        return (int) settings.site(siteId).integer(RETENTION_KEY, DEFAULT_RETENTION_DAYS, 1, MAX_RETENTION_DAYS);
    }

    /**
     * Records that {@code rawQuery} found nothing on {@code siteId}. A no-op when recording is off for the site,
     * when the term normalizes to nothing, or when it looks like personal data. Never throws and never blocks on
     * disk — the public search path calls it.
     */
    public void record(String siteId, String rawQuery) {
        try {
            if (siteId == null || !enabled(siteId)) {
                return;
            }
            String term = normalize(rawQuery);
            if (term == null || looksPersonal(term)) {
                return;
            }
            long now = clock.millis();
            SiteTerms site = site(siteId);
            synchronized (site) {
                if (term.equals(site.lastTerm) && now - site.lastAtMillis < REPEAT_WINDOW.toMillis()) {
                    return; // the same render seen twice (two boxes on one page) — count the search once
                }
                site.lastTerm = term;
                site.lastAtMillis = now;
                MutableEntry entry = site.terms.get(term);
                if (entry == null) {
                    if (site.terms.size() >= MAX_TERMS) {
                        evictOne(site.terms);
                    }
                    site.terms.put(term, new MutableEntry(1, now));
                } else {
                    entry.count++;
                    entry.lastSeenMillis = Math.max(entry.lastSeenMillis, now);
                }
                site.dirty = true;
            }
        } catch (RuntimeException e) {
            // Deliberately no term in the message: it is visitor text that may be personal data.
            log.warn("could not record a no-result search for site '{}': {}", siteId, e.getClass().getSimpleName());
        }
    }

    /**
     * The site's recorded terms within retention, most frequent first (then most recently seen, then
     * alphabetical). Read-only; empty when nothing has been recorded.
     */
    public List<Entry> list(String siteId) {
        long cutoff = cutoffMillis(siteId);
        SiteTerms site = site(siteId);
        List<Entry> out = new ArrayList<>();
        synchronized (site) {
            site.terms.forEach((term, e) -> {
                if (e.lastSeenMillis >= cutoff) {
                    out.add(new Entry(term, e.count, Instant.ofEpochMilli(e.lastSeenMillis)));
                }
            });
        }
        out.sort(Comparator.comparingLong(Entry::count).reversed()
                .thenComparing(Entry::lastSeen, Comparator.reverseOrder())
                .thenComparing(Entry::term));
        return List.copyOf(out);
    }

    /** Forgets every recorded term for {@code siteId}, in memory and on disk. */
    public void clear(String siteId) throws IOException {
        SiteTerms site = site(siteId);
        synchronized (site) {
            site.terms.clear();
            site.lastTerm = null;
            site.dirty = false;
            files.delete(siteId, FILE);
        }
    }

    /** Writes every site whose list changed since its last write (pruning expired terms first). */
    public void flush() {
        for (Map.Entry<String, SiteTerms> e : sites.entrySet()) {
            String siteId = e.getKey();
            SiteTerms site = e.getValue();
            synchronized (site) {
                if (!site.dirty) {
                    continue;
                }
                long cutoff = cutoffMillis(siteId);
                site.terms.values().removeIf(entry -> entry.lastSeenMillis < cutoff);
                String text = serialize(site.terms);
                if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                    log.warn("not writing an oversized no-result search list for site '{}'", siteId);
                    continue;
                }
                try {
                    files.writeText(siteId, FILE, text);
                    site.dirty = false;
                } catch (IOException | RuntimeException ex) {
                    log.warn("could not write the no-result search list for site '{}': {}", siteId, ex.toString());
                }
            }
        }
    }

    /**
     * A site's files were replaced wholesale (restored from a backup, or deleted): drop the in-memory list
     * <b>without</b> writing it, so the next read loads whatever the site folder now holds. Called by the plugin's
     * {@code SiteEventListener} for a wholesale lifecycle event.
     */
    void forget(String siteId) {
        sites.remove(siteId);
    }

    /** Stops the background flush and writes what is pending (the host calls it on shutdown). */
    @Override
    public void close() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("no-result search flush failed: {}", e.toString());
        }
    }

    private long cutoffMillis(String siteId) {
        return clock.millis() - Duration.ofDays(retentionDays(siteId)).toMillis();
    }

    /**
     * The site's list, loaded on first use. A list whose file could not be read is handed out but <b>not kept</b>:
     * what is recorded into it is lost and it is never flushed, so an unreadable file is never overwritten with a
     * partial list; the next call tries to read the file again.
     */
    private SiteTerms site(String siteId) {
        SiteTerms known = sites.get(siteId);
        if (known != null) {
            return known;
        }
        SiteTerms loaded = load(siteId);
        if (loaded == null) {
            return new SiteTerms();
        }
        SiteTerms raced = sites.putIfAbsent(siteId, loaded);
        return raced != null ? raced : loaded;
    }

    /**
     * Reads the site's file (bounded; a missing, oversized or garbled file yields an empty or partial list);
     * {@code null} when the file exists but cannot be read.
     */
    private SiteTerms load(String siteId) {
        SiteTerms site = new SiteTerms();
        String text;
        try {
            Optional<FileEntry> stat = files.stat(siteId, FILE);
            if (stat.isEmpty() || stat.get().directory() || stat.get().size() <= 0) {
                return site;
            }
            if (stat.get().size() > MAX_FILE_BYTES) {
                log.warn("ignoring an oversized no-result search list for site '{}' ({} bytes)", siteId,
                        stat.get().size());
                return site;
            }
            text = files.readText(siteId, FILE).orElse("");
        } catch (IOException | RuntimeException e) {
            log.warn("could not read the no-result search list for site '{}': {}", siteId, e.toString());
            return null;
        }
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length != 3) {
                continue;
            }
            String term = normalize(parts[0]);
            if (term == null) {
                continue;
            }
            try {
                long count = Long.parseLong(parts[1].strip());
                long lastSeen = Long.parseLong(parts[2].strip());
                if (count < 1) {
                    continue;
                }
                MutableEntry existing = site.terms.get(term);
                if (existing != null) {
                    existing.count += count;
                    existing.lastSeenMillis = Math.max(existing.lastSeenMillis, lastSeen);
                    continue;
                }
                if (site.terms.size() >= MAX_TERMS) {
                    evictOne(site.terms);
                }
                site.terms.put(term, new MutableEntry(count, lastSeen));
            } catch (NumberFormatException e) {
                // a hand-edited or truncated line — skip it
            }
        }
        return site;
    }

    private static String serialize(Map<String, MutableEntry> terms) {
        StringBuilder b = new StringBuilder(terms.size() * 32);
        terms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> b.append(e.getKey()).append('\t').append(e.getValue().count).append('\t')
                        .append(e.getValue().lastSeenMillis).append('\n'));
        return b.toString();
    }

    /** Removes the least frequent term, the longest-unseen among equals (then the alphabetically last). */
    private static void evictOne(Map<String, MutableEntry> terms) {
        String victim = null;
        MutableEntry worst = null;
        for (Map.Entry<String, MutableEntry> e : terms.entrySet()) {
            MutableEntry m = e.getValue();
            if (worst == null || m.count < worst.count
                    || (m.count == worst.count && m.lastSeenMillis < worst.lastSeenMillis)
                    || (m.count == worst.count && m.lastSeenMillis == worst.lastSeenMillis
                        && e.getKey().compareTo(victim) > 0)) {
                victim = e.getKey();
                worst = m;
            }
        }
        if (victim != null) {
            terms.remove(victim);
        }
    }

    /**
     * The canonical form a term is counted under: Unicode NFC, control characters removed, whitespace runs
     * collapsed to one space, trimmed, lower-cased ({@link Locale#ROOT}), and cut to {@link #MAX_TERM_CHARS}
     * (never splitting a surrogate pair). {@code null} when nothing is left.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_TERM_CHARS * 2));
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = b.length() > 0;
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            if (pendingSpace) {
                b.append(' ');
                pendingSpace = false;
            }
            b.append(c);
        }
        String term = b.toString().toLowerCase(Locale.ROOT);
        if (term.length() > MAX_TERM_CHARS) {
            int end = MAX_TERM_CHARS;
            if (Character.isHighSurrogate(term.charAt(end - 1))) {
                end--;
            }
            term = term.substring(0, end).strip();
        }
        return term.isEmpty() ? null : term;
    }

    /**
     * Whether a normalized term looks like personal data that should not be stored: an email address (an
     * {@code @} with a dot somewhere after it), or a phone / ID number — seven or more digits in a run where
     * spaces, dashes, dots, slashes, parentheses and a plus sign between them are ignored.
     */
    static boolean looksPersonal(String term) {
        int at = term.indexOf('@');
        if (at > 0 && term.indexOf('.', at) > at + 1) {
            return true;
        }
        int digits = 0;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isDigit(c)) {
                if (++digits >= 7) {
                    return true;
                }
            } else if (c != ' ' && c != '-' && c != '.' && c != '/' && c != '(' && c != ')' && c != '+') {
                digits = 0;
            }
        }
        return false;
    }
}
