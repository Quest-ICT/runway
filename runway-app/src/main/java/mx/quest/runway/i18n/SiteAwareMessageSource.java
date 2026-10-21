package mx.quest.runway.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.storage.FileStamp;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A {@link MessageSource} that resolves a {@code #{}} key against the <b>current request's site</b>
 * bundle. For the bound site it delegates to a per-site
 * {@link ReloadableResourceBundleMessageSource} over {@code sites/<id>/i18n/messages[_<locale>].properties}.
 *
 * <p>By design there is <b>no app-level fallback</b>: a site's bundle is self-contained, so an
 * unresolved key surfaces as {@code ??key??} (a signal the bundle is missing something) rather than
 * silently borrowing an application string. Per-site sources are built lazily, cached, and evicted on
 * <b>any</b> change to the site (a delete/restore, a messages-editor save, or a config change that
 * re-homes the bundle files — a default-locale switch) plus the direct {@link #evictSite} call the editor
 * makes. Resolution with no bound site (e.g. an app-realm request) returns the code itself rather than failing.
 *
 * <p><b>Plugin bundles.</b> Each loaded plugin's {@code i18n/messages[_xx].properties} bundle is appended
 * <b>after</b> the site's own bundle in every per-site source, so a plugin's SPI code (a widget, a head
 * contributor, an endpoint) can localize its <em>public-facing</em> output by injecting this source and
 * resolving its own keys for the request locale — add-only, so a plugin can <b>never override</b> a site's
 * key (the site's own bundle is consulted first). This is the public/site-realm arm of the plugin i18n seam
 * (the operator console has the same, via {@code OperatorMessageSource}).
 *
 * <h2>Freshness: a page is rendered from the bundle its ETag describes</h2>
 * A per-site source never refreshes on a timer. It is built together with a {@link FileStamp} over every bundle
 * file it can read — the site's and each plugin's {@code messages*.properties} / {@code .xml}, plus their folders,
 * so a new locale file counts — and a {@linkplain BundleState fingerprint} of those files' bytes; when the stamp
 * says a file changed, the whole entry is replaced. A public page calls {@link #bundleState(String)} on
 * <b>every</b> request before it renders: that re-checks the stamp (one {@code stat} per file and folder), swaps
 * in a fresh source if anything changed, and returns the fingerprint the page validator folds into the ETag — so
 * the ETag describes exactly the bundle the render then reads.
 *
 * <p><i>Why:</i> before rc1 the validator folded the files' bytes while the source re-read them only every
 * {@code runway.i18n.cache-seconds} (and only when the file's millisecond timestamp moved). For that window after
 * an edit the old text went out under the new ETag, and a client or CDN that stored it kept revalidating it to
 * {@code 304}. Every other lookup — an error page, a plugin's off-request {@code Messages.site} — re-checks the
 * stamp at most once per {@code runway.i18n.cache-seconds} ({@code 0} = on every lookup), so a {@code #{}} key
 * still costs a map read.
 */
@Component
public class SiteAwareMessageSource implements MessageSource {

    private static final String I18N_DIR = "i18n";
    private static final String BASENAME = "messages";
    /** The bundle files a {@link ReloadableResourceBundleMessageSource} reads: {@code .properties} and {@code .xml}. */
    private static final List<String> BUNDLE_SUFFIXES = List.of(".properties", ".xml");
    /** Above this a bundle file is fingerprinted by size + modification time, not read into the heap. */
    private static final long MAX_FINGERPRINT_BYTES = 10L * 1024 * 1024;

    private final SiteResolver siteResolver;
    private final StorageLayout layout;
    /** How often a non-page lookup re-checks the files; a page request always does ({@link #bundleState}). */
    private final long cacheMillis;
    private final MessageSource noSiteFallback;
    /** Each loaded plugin's {@code i18n/} folder, consulted after the site's own (add-only). */
    private final List<Path> pluginI18nDirs;
    private final ConcurrentMap<String, SiteBundles> perSite = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /**
     * What a public page's ETag folds for the site's message bundles: a fingerprint of exactly the bundle files the
     * site's current source reads, and the newest of their modification times.
     *
     * @param fingerprint    hex SHA-256 over every bundle file's name and bytes (the site's folder, then each plugin's)
     * @param newestModified the newest bundle file's modification time in epoch millis; {@code 0} when there is none
     */
    public record BundleState(String fingerprint, long newestModified) {
    }

    /**
     * One site's loaded bundles: the message source (which caches each file forever), the stamp of the files it can
     * read, and their fingerprint — taken together, the stamp first, so an edit racing the build makes the stamp
     * report "changed" on the next check: one extra rebuild, never a stale source kept.
     */
    private static final class SiteBundles {
        final MessageSource source;
        final FileStamp stamp;
        final BundleState state;
        /** When a throttled (non-page) lookup last checked the stamp. */
        volatile long checkedAt;

        SiteBundles(MessageSource source, FileStamp stamp, BundleState state, long checkedAt) {
            this.source = source;
            this.stamp = stamp;
            this.state = state;
            this.checkedAt = checkedAt;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SiteAwareMessageSource(SiteResolver siteResolver, StorageLayout layout, I18nProperties properties,
                                  ObjectProvider<PluginRuntime> pluginRuntime) {
        this(siteResolver, layout, properties, pluginI18nDirs(pluginRuntime), System::currentTimeMillis);
    }

    /** Convenience for tests / no-plugin contexts. */
    public SiteAwareMessageSource(SiteResolver siteResolver, StorageLayout layout, I18nProperties properties) {
        this(siteResolver, layout, properties, List.of(), System::currentTimeMillis);
    }

    /** Tests: explicit plugin bundle folders and wall clock (milliseconds since the epoch). */
    SiteAwareMessageSource(SiteResolver siteResolver, StorageLayout layout, I18nProperties properties,
                           List<Path> pluginI18nDirs, LongSupplier clock) {
        this.siteResolver = siteResolver;
        this.layout = layout;
        this.cacheMillis = Math.max(0, properties.getCacheSeconds()) * 1000L;
        this.noSiteFallback = emptySource();
        this.pluginI18nDirs = List.copyOf(pluginI18nDirs);
        this.clock = clock;
    }

    private static List<Path> pluginI18nDirs(ObjectProvider<PluginRuntime> pluginRuntime) {
        PluginRuntime runtime = pluginRuntime.getIfAvailable(
                () -> PluginRuntime.empty(SiteAwareMessageSource.class.getClassLoader()));
        List<Path> dirs = new ArrayList<>();
        for (LoadedPlugin plugin : runtime.loaded()) {
            if (Files.isDirectory(plugin.installDir().resolve(I18N_DIR))) {
                dirs.add(plugin.installDir().resolve(I18N_DIR));
            }
        }
        return List.copyOf(dirs);
    }

    private MessageSource current() {
        Site site = siteResolver.current().orElse(null);
        if (site == null) {
            return noSiteFallback;
        }
        return bundles(site.id(), false).source;
    }

    /**
     * The message source of an <b>explicitly named</b> site — the site's own bundle, then every plugin bundle —
     * independent of which site (if any) is bound to the calling thread. This is the off-request lookup the SPI's
     * {@code Messages.site} needs: a background task, or a request for one site that renders text for another,
     * must not resolve against whatever the thread happens to be bound to. Same cached instance as the thread-bound
     * path, so both see the same evictions.
     *
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     */
    public MessageSource forSite(String siteId) {
        requireSafe(siteId);
        return bundles(siteId, false).source;
    }

    /**
     * The fingerprint of the bundles the site's next render reads — after re-checking the files, so a file edited on
     * disk a moment ago is both in the fingerprint and in what the render reads. The page validator calls this on
     * every public page request, before rendering. Cost: one {@code stat} per bundle file and folder; the files are
     * read again only when one of them changed.
     *
     * @return the current state; never {@code null}
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     */
    public BundleState bundleState(String siteId) {
        requireSafe(siteId);
        return bundles(siteId, true).state;
    }

    /** Drops a site's cached bundle so its files are re-read (on delete/restore, or a messages edit). */
    public void evictSite(String siteId) {
        perSite.remove(siteId);
    }

    @EventListener
    @Order(KernelEventOrder.RESOURCES)
    public void onSiteChanged(SiteChangedEvent event) {
        // Evict on ANY site change, not only wholesale replacement: a config change can now re-home the i18n
        // bundle files (a default-locale switch migrates them — SiteMessagesStore#migrateDefaultLocale), so the
        // cached per-site bundle must be dropped and re-read. Eviction is cheap (a lazy rebuild on next use).
        evictSite(event.siteId());
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        return current().getMessage(code, args, defaultMessage, locale);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
        return current().getMessage(code, args, locale);
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
        return current().getMessage(resolvable, locale);
    }

    /**
     * The site's current bundles, re-checked against the disk when {@code always} is set or the throttle interval has
     * passed. A stale entry is replaced only while it is still the mapped one, so racing checks rebuild once.
     */
    private SiteBundles bundles(String siteId, boolean always) {
        SiteBundles current = perSite.computeIfAbsent(siteId, this::build);
        long now = clock.getAsLong();
        if (!always && now - current.checkedAt < cacheMillis) {
            return current;
        }
        current.checkedAt = now;
        if (current.stamp.isCurrent()) {
            return current;
        }
        return perSite.compute(siteId, (id, mapped) -> mapped == null || mapped == current ? build(id) : mapped);
    }

    /**
     * Builds a site's bundles: lists the bundle files, stamps them and their folders, fingerprints them, then
     * creates a message source that caches each file <b>forever</b> — freshness comes from replacing the whole
     * entry when the stamp reports a change, never from a timer. The source reads a file lazily, after the stamp;
     * an edit landing in between leaves it holding newer text than the stamp describes, and the next check rebuilds.
     */
    private SiteBundles build(String siteId) {
        // siteDir validates the id (traversal-safe); the bundle lives in the site's own folder. The site's own
        // bundle comes first (it wins), then every plugin bundle (add-only — a plugin never overrides a site key).
        List<Path> dirs = new ArrayList<>();
        dirs.add(layout.siteDir(siteId).resolve(I18N_DIR));
        dirs.addAll(pluginI18nDirs);

        List<Path> stamped = new ArrayList<>(dirs);
        List<List<Path>> filesPerDir = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            List<Path> files = bundleFiles(dir);
            filesPerDir.add(files);
            stamped.addAll(files);
        }
        FileStamp stamp = FileStamp.of(stamped.toArray(Path[]::new));
        BundleState state = fingerprint(filesPerDir);

        ReloadableResourceBundleMessageSource source = new LanguageTagBundles();
        source.setBasenames(dirs.stream().map(dir -> "file:" + dir.resolve(BASENAME)).toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setCacheMillis(-1); // cache forever: the stamp decides when the whole entry is replaced
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false); // missing key → NoSuchMessage → Thymeleaf renders ??key??
        return new SiteBundles(source, stamp, state, clock.getAsLong());
    }

    /**
     * Spring's bundle reader, also accepting a file named with the BCP 47 tag ({@code messages_pt-BR.properties}),
     * which is how the Messages editor ({@link SiteMessagesStore}) names a region language's file. Spring itself only
     * looks for {@code messages_pt_BR}, so before 1.0.0-rc2 a site offering {@code pt-BR} never saw its translations
     * (they fell back to {@code messages_pt}, then the default language). The tag form is tried first, then Spring's
     * own names, so a hand-made {@code messages_pt_BR.properties} keeps working.
     */
    static final class LanguageTagBundles extends ReloadableResourceBundleMessageSource {
        @Override
        protected List<String> calculateFilenamesForLocale(String basename, Locale locale) {
            List<String> names = super.calculateFilenamesForLocale(basename, locale);
            String tag = locale.toLanguageTag();
            if (!tag.contains("-")) {
                return names;
            }
            List<String> out = new ArrayList<>(names.size() + 1);
            out.add(basename + "_" + tag);
            out.addAll(names);
            return out;
        }
    }

    /** The bundle files in {@code dir} ({@code messages.properties}, {@code messages_es.xml}, …), sorted; none if absent. */
    private static List<Path> bundleFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(SiteAwareMessageSource::isBundleFile).sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("listing message bundles under " + dir, ex);
        }
    }

    private static boolean isBundleFile(Path file) {
        String name = file.getFileName().toString();
        boolean named = name.startsWith(BASENAME + "_") || name.startsWith(BASENAME + ".");
        return named && BUNDLE_SUFFIXES.stream().anyMatch(name::endsWith) && Files.isRegularFile(file);
    }

    /**
     * Fingerprints the bundle files folder by folder: the folder's position, then each file's name and bytes, every
     * component length-prefixed so nothing can shift across a boundary. A file over {@link #MAX_FINGERPRINT_BYTES}
     * folds its size and modification time instead of its bytes.
     */
    private static BundleState fingerprint(List<List<Path>> filesPerDir) {
        MessageDigest digest = sha256();
        long newest = 0L;
        for (int i = 0; i < filesPerDir.size(); i++) {
            update(digest, ("dir#" + i).getBytes(StandardCharsets.UTF_8));
            for (Path file : filesPerDir.get(i)) {
                update(digest, file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                try {
                    long size = Files.size(file);
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    newest = Math.max(newest, mtime);
                    update(digest, size > MAX_FINGERPRINT_BYTES
                            ? ("large:" + size + ":" + mtime).getBytes(StandardCharsets.UTF_8)
                            : Files.readAllBytes(file));
                } catch (NoSuchFileException vanished) {
                    update(digest, new byte[0]); // deleted mid-build; the stamp reports it on the next check
                } catch (IOException ex) {
                    throw new UncheckedIOException("reading message bundle " + file, ex);
                }
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return new BundleState(hex.toString(), newest);
    }

    /** Folds one self-delimiting component: {@code <length>:<bytes>}. */
    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
    }

    private static void requireSafe(String siteId) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
    }

    private static MessageSource emptySource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setUseCodeAsDefaultMessage(true); // no site → echo the code rather than throw
        return source;
    }
}
