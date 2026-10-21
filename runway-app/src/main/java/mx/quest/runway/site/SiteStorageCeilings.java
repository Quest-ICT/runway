package mx.quest.runway.site;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Each site's <b>storage ceiling</b>: the most its files ({@code sites/<id>/static/}, the Static files and Documents
 * screens) may take on disk, so one site cannot fill the data disk every site shares. The operator sets it per
 * site on the Sites screen, and the
 * site sees it read-only on its Static files screen.
 *
 * <h2>Where a ceiling comes from, first match wins</h2>
 * <ol>
 *   <li><b>The Sites screen</b> — {@code siteStorageCeilings} in {@code external/platform.yml}. Read live: the next
 *       upload sees a change.</li>
 *   <li><b>The platform default</b> — {@code runway.static-files.default-max-bytes} (Settings → Server settings, or
 *       the host file). Bound at startup.</li>
 * </ol>
 * {@code 0} means unlimited at both levels, and {@code 0} is the default, so an upgrade changes nothing.
 *
 * <h2>What a ceiling does, and does not do</h2>
 * It <b>refuses a new write</b> that would take the folder over it ({@link #write}). It never deletes, hides or
 * truncates a file: a site already over its ceiling (the operator lowered it, or a restore brought more back) keeps
 * every file, serves them, and can still delete; a replacement that does not grow the folder is always accepted, so
 * such a site can also shrink a file. Restores, the page editor, messages, logs and plugin files do not pass
 * through here (they are not in {@code static/}, or, for a restore, must bring the site back whole).
 *
 * <h2>Counting</h2>
 * Usage is the sum of the regular files under {@code static/} (symbolic links are not followed). A check walks the
 * folder afresh while holding the site's lock, so two uploads at once cannot both slip under the ceiling on one
 * node; a write counts the incoming bytes and subtracts the file it replaces. Screens read {@link #usedBytes},
 * which is cached per site, dropped on every write or delete through this class, on every site change, and after
 * {@value #USAGE_TTL_SECONDS} seconds (files copied onto the server by hand).
 */
@Component
public class SiteStorageCeilings {

    /** The platform default's key: {@code 0} = unlimited. */
    public static final String DEFAULT_KEY = "runway.static-files.default-max-bytes";

    /** How long a screen may show a cached usage figure without a write or delete through the console. */
    static final long USAGE_TTL_SECONDS = 30;

    private static final String ROOT = "static";

    /** Where a site's ceiling comes from. */
    public enum Source {
        /** The operator set this site's ceiling on the Sites screen. */
        SITE,
        /** No ceiling of its own: the platform default applies. */
        DEFAULT
    }

    /**
     * A site's ceiling.
     *
     * @param bytes  the ceiling in bytes; {@code 0} = unlimited
     * @param source where it comes from
     */
    public record Ceiling(long bytes, Source source) {

        /** Whether the site is limited at all. */
        public boolean limited() {
            return bytes > 0;
        }
    }

    /**
     * Thrown when a write would take a site's files over its ceiling. Nothing was written.
     */
    public static final class OverCeiling extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final long usedBytes;
        private final long ceilingBytes;

        OverCeiling(String siteId, long usedBytes, long incoming, long ceilingBytes) {
            super("site '" + siteId + "' would use " + (usedBytes + incoming) + " bytes, over its storage ceiling of "
                    + ceilingBytes + " bytes");
            this.usedBytes = usedBytes;
            this.ceilingBytes = ceilingBytes;
        }

        /** What the site's files used before the refused write. */
        public long usedBytes() {
            return usedBytes;
        }

        /** The ceiling that refused it. */
        public long ceilingBytes() {
            return ceilingBytes;
        }
    }

    /** A write to run once the ceiling allows it. */
    @FunctionalInterface
    public interface Write {
        /** Performs the write. */
        void run() throws IOException;
    }

    private final PlatformSettingsStore platform;
    private final StorageLayout layout;
    private final long defaultBytes;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Cache<String, Long> usage = Caffeine.newBuilder()
            .maximumSize(4096).expireAfterWrite(Duration.ofSeconds(USAGE_TTL_SECONDS)).build();

    public SiteStorageCeilings(PlatformSettingsStore platform, StorageLayout layout,
                               @Value("${" + DEFAULT_KEY + ":0}") long defaultBytes) {
        this.platform = platform;
        this.layout = layout;
        this.defaultBytes = Math.max(0, defaultBytes);
    }

    /** {@code siteId}'s ceiling and its source. */
    public Ceiling of(String siteId) {
        Long own = platform.get().siteStorageCeilings().get(siteId);
        if (own != null) {
            return new Ceiling(Math.max(0, own), Source.SITE);
        }
        return new Ceiling(defaultBytes, Source.DEFAULT);
    }

    /** The platform default ceiling in bytes ({@code 0} = unlimited). */
    public long defaultBytes() {
        return defaultBytes;
    }

    /** What {@code siteId}'s files use now, in bytes, for a screen (cached; see the class comment). */
    public long usedBytes(String siteId) {
        return usage.get(siteId, this::measure);
    }

    /**
     * Runs {@code write} if it keeps {@code siteId}'s files within the ceiling, holding the site's lock across the
     * check and the write. Accepted when the site is unlimited, when {@code incoming} is no larger than
     * {@code replaced} (the folder does not grow), or when the usage minus {@code replaced} plus {@code incoming} is
     * at most the ceiling.
     *
     * @param incoming the size of what is written, in bytes
     * @param replaced the current size of the file it replaces ({@code 0} or negative for a new file), read under
     *                 the lock so a concurrent replacement of the same file is counted once
     * @throws OverCeiling when the write would take the folder over the ceiling (nothing is written)
     * @throws IOException when the write itself fails
     */
    public void write(String siteId, long incoming, LongSupplier replaced, Write write) throws IOException {
        Ceiling ceiling = of(siteId);
        if (!ceiling.limited()) {
            try {
                write.run();
            } finally {
                usage.invalidate(siteId);
            }
            return;
        }
        synchronized (locks.computeIfAbsent(siteId, id -> new Object())) {
            long used = measure(siteId);
            long kept = Math.max(0, replaced.getAsLong());
            if (incoming > kept && used - kept + incoming > ceiling.bytes()) {
                usage.put(siteId, used);
                throw new OverCeiling(siteId, used, incoming - kept, ceiling.bytes());
            }
            try {
                write.run();
            } finally {
                usage.invalidate(siteId);
            }
        }
    }

    /** Drops {@code siteId}'s cached usage: call after deleting one of its files. */
    public void changed(String siteId) {
        usage.invalidate(siteId);
    }

    /** Any site change (a restore brings files back wholesale, a delete removes them) drops its cached usage. */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        usage.invalidate(event.siteId());
    }

    /** The bytes of the regular files under {@code sites/<id>/static/}, without following symbolic links. */
    long measure(String siteId) {
        Path root = layout.siteDir(siteId).resolve(ROOT);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        AtomicLong total = new AtomicLong();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        total.addAndGet(attrs.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failed) {
                    return FileVisitResult.CONTINUE; // gone or unreadable mid-walk: it takes no space we can count
                }
            });
        } catch (IOException unreadable) {
            // the visitor swallows per-file failures; a root that cannot be opened counts what was seen
        }
        return total.get();
    }
}
