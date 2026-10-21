package mx.quest.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads and writes the site's {@link Showcase} ({@value #FILE} in the plugin's per-site folder) and — the point of
 * this class — <b>announces every write with {@link ContentChanges}</b>.
 *
 * <p><b>When to use {@code ContentChanges}.</b> After every mutation of content your {@code ContentSource} serves:
 * an item created, edited, deleted, published, unpublished, or the whole corpus replaced (pass a {@code null} id).
 * The host cannot see your files change; the call evicts routing, the content listing, sitemaps and search indexes on
 * every node of a cluster and notifies every {@code SiteEventListener} before it returns. Call it from the code that
 * made the change, <b>after</b> the write and outside your own locks (listeners run synchronously on this thread),
 * and never from a {@code SiteEventListener} callback — the kernel refuses that. It is plugin-scoped like
 * {@code PluginSettings}: it only accepts a source key this plugin contributes.
 *
 * <p>The parsed file is cached per site, because {@code enumerate} runs on every catalog rebuild and routing miss.
 * The cache is dropped by this store's own writes, by {@link DemoShowcaseSource}'s {@code SOURCE}-phase listener
 * (a change replayed from another node, a restored or deleted site), and after {@link #TTL} at the latest — the
 * backstop for a file edited by hand, which nobody announces.
 */
public class DemoShowcaseStore {

    /** The showcase file, relative to the plugin's per-site folder. */
    static final String FILE = "showcase.yml";
    /** The longest showcase text kept, in characters. */
    static final int MAX_TEXT = 2000;
    /** How long a cached read is trusted without any announcement. */
    static final Duration TTL = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(DemoShowcaseStore.class);

    /** The on-disk shape; the instant is written as ISO-8601 text. */
    record ShowcaseFile(String text, String updatedAt) {
    }

    private record Cached(Optional<Showcase> value, long loadedAtNanos) {
    }

    private final PluginSiteFiles files;
    private final ContentChanges changes;
    private final LongSupplier nanoClock;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public DemoShowcaseStore(PluginSiteFiles files, ContentChanges changes) {
        this(files, changes, System::nanoTime);
    }

    DemoShowcaseStore(PluginSiteFiles files, ContentChanges changes, LongSupplier nanoClock) {
        this.files = files;
        this.changes = changes;
        this.nanoClock = nanoClock;
    }

    /** The site's published showcase; empty when none is published or the file cannot be read (logged). */
    public Optional<Showcase> current(String siteId) {
        Cached cached = cache.get(siteId);
        if (fresh(cached)) {
            return cached.value();
        }
        // Load under the site's lock, so a read racing a publish can never put the old file back into the cache.
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            cached = cache.get(siteId);
            if (fresh(cached)) {
                return cached.value();
            }
            Optional<Showcase> loaded = load(siteId);
            cache.put(siteId, new Cached(loaded, nanoClock.getAsLong()));
            return loaded;
        }
    }

    private boolean fresh(Cached cached) {
        return cached != null && nanoClock.getAsLong() - cached.loadedAtNanos() < TTL.toNanos();
    }

    /**
     * Publishes {@code text} (stripped, capped at {@value #MAX_TEXT} characters) as the site's showcase, replacing
     * any previous one, then announces the change.
     *
     * @throws IllegalArgumentException when the text is blank
     * @throws UncheckedIOException     when the file cannot be written (nothing is announced)
     */
    public Showcase publish(String siteId, String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("the showcase text is blank");
        }
        if (clean.length() > MAX_TEXT) {
            clean = clean.substring(0, MAX_TEXT);
        }
        Showcase showcase = new Showcase(clean, Instant.now());
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            try {
                files.writeText(siteId, FILE, yaml.writeValueAsString(
                        new ShowcaseFile(showcase.text(), showcase.updatedAt().toString())));
            } catch (IOException e) {
                throw new UncheckedIOException("publishing the runway-demo showcase of site " + siteId, e);
            }
            cache.put(siteId, new Cached(Optional.of(showcase), nanoClock.getAsLong()));
        }
        // Written; now tell the host — outside our lock, because every listener runs on this thread before it returns.
        changes.changed(siteId, DemoShowcaseSource.SOURCE_KEY, DemoShowcaseSource.ID);
        return showcase;
    }

    /**
     * Unpublishes the site's showcase (deletes the file) and announces the change, so the page, the sitemap entry
     * and any search hit disappear at once.
     *
     * @return {@code true} if a showcase was published; {@code false} (and nothing announced) when there was none
     * @throws UncheckedIOException when the file cannot be deleted
     */
    public boolean unpublish(String siteId) {
        boolean deleted;
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            try {
                deleted = files.delete(siteId, FILE);
            } catch (IOException e) {
                throw new UncheckedIOException("unpublishing the runway-demo showcase of site " + siteId, e);
            }
            cache.put(siteId, new Cached(Optional.empty(), nanoClock.getAsLong()));
        }
        if (deleted) {
            changes.changed(siteId, DemoShowcaseSource.SOURCE_KEY, DemoShowcaseSource.ID);
        }
        return deleted;
    }

    /** Drops the cached read of {@code siteId}, so the next {@link #current} reads the file again. */
    public void evict(String siteId) {
        cache.remove(siteId);
    }

    private Optional<Showcase> load(String siteId) {
        try {
            Optional<String> text = files.readText(siteId, FILE);
            if (text.isEmpty() || text.get().isBlank()) {
                return Optional.empty();
            }
            ShowcaseFile file = yaml.readValue(text.get(), ShowcaseFile.class);
            if (file == null || file.text() == null || file.text().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Showcase(file.text(), parseInstant(file.updatedAt())));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            // IllegalArgumentException: an unsafe site id or a path escaping the folder. Serve nothing, never a 500.
            log.warn("runway-demo showcase of site {} could not be read: {}", siteId, e.toString());
            return Optional.empty();
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? Instant.EPOCH : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
