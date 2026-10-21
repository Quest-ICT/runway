package mx.quest.runway.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import org.springframework.stereotype.Component;

/**
 * Reads and writes {@code external/platform.yml} — the {@link PlatformSettings}. A single small file at the
 * external root (part of the trusted computing base: never web-served, and deliberately <b>outside every
 * tenant folder</b> so the platform's own decisions never leak into a tenant backup). Cached in-process and
 * refreshed on write; an absent file reads as {@link PlatformSettings#EMPTY}.
 *
 * <p>Read-hot on the site-resolution fallback path ({@link mx.quest.runway.site.SiteResolver} consults the
 * catch-all default site for every unmatched host), hence the cache.
 *
 * <h2>One file, one monitor: every change goes through {@link #update}</h2>
 * Many screens write this one file (Platform settings, Server settings, site quotas, embed hosts, log levels), and
 * so does the site-deletion listener. A change is a read-modify-write: {@link #update} runs it under this store's
 * monitor, against the latest settings, so two changes to different fields made at the same moment both survive.
 * There is deliberately no public "save this whole record": {@code save(get().withX(..))} reads outside the lock
 * and silently loses whatever another writer saved in between.
 */
@Component
public class PlatformSettingsStore {

    private static final String FILE = "platform.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private volatile PlatformSettings cached;

    public PlatformSettingsStore(StorageLayout layout, YamlStore store) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The current platform settings (never {@code null}; {@link PlatformSettings#EMPTY} when the file is absent). */
    public PlatformSettings get() {
        PlatformSettings snapshot = cached;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cached;
                if (snapshot == null) {
                    snapshot = load();
                    cached = snapshot;
                }
            }
        }
        return snapshot;
    }

    /**
     * Atomically changes the settings: {@code change} receives the latest settings and returns the new ones, which
     * are written to disk and are what every later reader sees. Runs under this store's monitor, so keep
     * {@code change} pure and cheap (no events; a check that must see the latest state, such as whether a site still
     * exists, may read it); if it throws, nothing is written. A change that returns the settings unchanged (the same
     * object, or equal ones) writes nothing: a screen that saves the same value again does not rewrite the file.
     *
     * @return the settings now in force
     */
    public synchronized PlatformSettings update(UnaryOperator<PlatformSettings> change) {
        Objects.requireNonNull(change, "change");
        PlatformSettings current = get();
        PlatformSettings next = Objects.requireNonNull(change.apply(current), "change result");
        if (next == current || next.equals(current)) {
            return current;
        }
        try {
            store.write(file(), next);
        } catch (IOException e) {
            throw new UncheckedIOException("writing platform.yml", e);
        }
        cached = next;
        return next;
    }

    private PlatformSettings load() {
        try {
            return store.read(file(), PlatformSettings.class).orElse(PlatformSettings.EMPTY);
        } catch (IOException e) {
            throw new UncheckedIOException("reading platform.yml", e);
        }
    }

    private Path file() {
        return layout.external().resolve(FILE);
    }
}
