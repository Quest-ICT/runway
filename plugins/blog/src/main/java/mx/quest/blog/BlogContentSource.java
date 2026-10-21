package mx.quest.blog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The blog as a kernel {@link ContentSource}: entries become first-class virtual pages — routed through the
 * full locale-dispatch ladder, rendered in the tenant's master chrome around this plugin's own body template,
 * listed in sitemap.xml/llms.txt with {@code hreflang} + {@code <lastmod>}, indexed by search, and linkable as
 * {@code ${urls['blog:<id>']}} — all host-provided; this class only answers the contract questions.
 *
 * <h2>The contract, honored</h2>
 * <ul>
 *   <li>{@link #enumerate}: published entries only, metadata-only ({@link BlogStore#list} reads
 *       {@code entry.yml} files, never a body), each with {@code updatedAt} from file times, cached until an
 *       announced change: a console save moves the render ETag with no explicit bump; a body edited on disk
 *       is served at once but under the OLD ETag until a site reload (docs/BLOG-PLUGIN.md §3).</li>
 *   <li>{@link #resolve}: an O(1) probe of a per-site {@code path|lang → id} index (printable {@code |}),
 *       rebuilt lazily after any invalidation — required because {@code content.arbitrary-paths} makes this
 *       source run on <b>every</b> routing miss of an enabled site.</li>
 *   <li>{@link #body}: Markdown → HTML via the bundled commonmark, fetched only for an actual 200 render.
 *       Entry Markdown is operator-authored, first-party trusted content (raw HTML passes through, exactly as
 *       a page author may write markup).</li>
 *   <li>{@link #indexableText}: the same rendered HTML, for search.</li>
 * </ul>
 *
 * <h2>Index invalidation: a SOURCE-phase {@link SiteEventListener}</h2>
 * The console announces every mutation through {@code ContentChanges.changed}; the kernel delivers it back here as
 * a {@code CONTENT} {@link CatalogChange} for this source's key, and this class drops the site's index. A site
 * lifecycle event drops it too (a <b>backup restore</b> replaces the entry files wholesale), and so does a save of
 * this plugin's own settings (a prefix change re-keys every URL). Running in the {@link Phase#SOURCE SOURCE} phase
 * is what keeps consumers honest: the kernel delivers every SOURCE listener first, evicts its content listing once
 * more, and only then lets sitemap and search consumers re-read — so none of them can rebuild from this source's
 * stale index. With clustering ON, the kernel relays the change to every node and delivers it there too
 * ({@code local == false}), so every node's index converges.
 */
public class BlogContentSource implements ContentSource, SiteEventListener {

    public static final String KEY = "blog";

    private static final Logger log = LoggerFactory.getLogger(BlogContentSource.class);

    private final BlogStore store;
    private final PluginSettings settings;
    private final ContentCatalogView catalog;
    private final Parser markdown = Parser.builder().build();
    private final HtmlRenderer html = HtmlRenderer.builder().build();

    /** Per-site URL index: {@code path|lang → entryId}; dropped on any content/config/site change. */
    private final ConcurrentMap<String, Map<String, String>> index = new ConcurrentHashMap<>();
    /** Per-site enumerated refs — the metadata cache behind {@link #enumerate}, dropped by the SAME events, so
     * the per-render urlMap and per-request sitemap don't re-read the entries tree every time. */
    private final ConcurrentMap<String, List<ContentRef>> refs = new ConcurrentHashMap<>();

    public BlogContentSource(BlogStore store, PluginSettings settings, ContentCatalogView catalog) {
        this.store = store;
        this.settings = settings;
        this.catalog = catalog;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Capabilities capabilities() {
        // Custom paths escape the prefix; indexable-text opts this source into the search integration.
        return Capabilities.of(CAP_ARBITRARY_PATHS, CAP_INDEXABLE_TEXT);
    }

    /** The site's effective settings — the one place the source and the console read them. */
    public BlogSettings settings(String siteId) {
        return BlogSettings.of(settings, catalog, siteId);
    }

    /**
     * The entry's indexable material for {@code locale}: the Markdown body rendered to HTML — the SAME
     * transform the {@link #body} render uses. Engines strip <em>HTML</em> markup (they were built for page
     * HTML bodies), so handing them the rendered HTML yields clean prose in the index and in result snippets;
     * handing them the raw Markdown would leak {@code ## heading}, {@code **bold**} and {@code [text](url)}
     * link syntax straight into the terms and the visible snippet. Empty when the entry has no body for this
     * locale.
     */
    @Override
    public String indexableText(SiteView site, String id, Locale locale) {
        String source = store.readBody(site.id(), id, locale.getLanguage());
        return source.isEmpty() ? "" : html.render(markdown.parse(source));
    }

    @Override
    public List<ContentRef> enumerate(SiteView site) {
        BlogSettings current = settings(site.id());
        if (!current.enabled()) {
            return List.of();
        }
        return refs.computeIfAbsent(site.id(), siteId -> {
            List<ContentRef> out = new ArrayList<>();
            for (BlogEntry entry : store.list(siteId)) {
                if (entry.published()) {
                    out.add(toRef(entry, current));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
        String id = index.computeIfAbsent(site.id(), sid -> buildIndex(site))
                .get(path + '|' + locale.getLanguage());
        if (id == null) {
            return Optional.empty();
        }
        // Answer from the SAME cached refs the index was built from (one settings read, no torn view).
        for (ContentRef ref : enumerate(site)) {
            if (ref.id().equals(id)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    @Override
    public ContentBody body(SiteView site, String id, Locale locale) {
        String source = store.readBody(site.id(), id, locale.getLanguage());
        if (source.isBlank()) {
            return ContentBody.empty();
        }
        // Editorial metadata the body template may render. Absent keys are simply not put, so a template can
        // test for presence — an entry with no byline must render no byline, not an empty one.
        Map<String, String> attributes = new LinkedHashMap<>();
        store.read(site.id(), id).ifPresent(entry -> {
            if (entry.publishedOn() != null) {
                attributes.put("publishedOn", entry.publishedOn());
            }
            if (entry.author() != null) {
                attributes.put("author", entry.author());
            }
        });
        return new ContentBody(html.render(markdown.parse(source)), attributes);
    }

    // --- invalidation (SiteEventListener, SOURCE phase) --------------------------------------------------------

    /** {@link Phase#SOURCE}: this source's own index must be dropped before any consumer re-reads the listing. */
    @Override
    public Phase phase() {
        return Phase.SOURCE;
    }

    /** Any lifecycle event — including a backup restore, which replaced the entry files wholesale. */
    @Override
    public void onSiteLifecycle(SiteLifecycleEvent event) {
        drop(event.siteId());
    }

    /** This plugin's own settings were saved: a prefix or template change re-keys every URL and every ref. */
    @Override
    public void onSettingsChanged(SettingsChange change) {
        drop(change.siteId());
    }

    /** This source's own content changed (announced by the console, on this node or a peer). */
    @Override
    public void onCatalogChanged(CatalogChange change) {
        if (change.cause() == CatalogChange.Cause.CONTENT && KEY.equals(change.sourceKey())) {
            drop(change.siteId());
        }
    }

    private void drop(String siteId) {
        index.remove(siteId);
        refs.remove(siteId);
    }

    // --- mapping -----------------------------------------------------------------------------------------------

    /** Projects an entry onto the content noun under the site's current prefix settings. */
    private ContentRef toRef(BlogEntry entry, BlogSettings current) {
        String defaultPath = entry.customPath() != null ? entry.customPath()
                : current.pathFor(entry.effectiveSlug());
        Map<String, String> localizedPaths = new LinkedHashMap<>();
        entry.localizedSlugs().forEach((lang, slug) ->
                localizedPaths.put(lang, current.pathFor(slug)));
        List<Locale> locales = entry.locales().stream().map(Locale::forLanguageTag).toList();
        return new ContentRef(entry.id(), defaultPath, localizedPaths, locales, entry.title(),
                entry.description(), null, current.bodyTemplate(), entry.published(), entry.updatedAt(),
                false, null, null, Map.of(), entry.author(), publishedAt(entry));
    }

    /**
     * The entry's own {@code publishedOn} as an instant, for {@link ContentRef#publishedAt()} — so each post
     * carries ITS OWN publication date across the SPI boundary.
     *
     * <p>This is what makes per-post dates truthful. Without it, the discoverability plugin would have to take
     * {@code datePublished} from the per-site {@code content.schema} block, which matches by path prefix — so ONE
     * {@code /blog/*} entry would cover every post. A site's only honest options would be to emit no dates at all,
     * or to assert a single date identically on every post: a statement false of nearly every page carrying it,
     * and exactly the kind of untrue markup structured-data policy prohibits.
     *
     * <p>Interpreted at <b>midnight UTC</b> on the stated day, matching how the kernel's page editor parses its
     * own date field, so a blog entry and a page dated the same day produce the same instant.
     *
     * <p>The entry's {@code author} travels the same way, so a post is attributed to whoever
     * actually wrote it rather than to one name shared by every post on the site.
     *
     * <p>An unparseable value yields {@code null} — no date. {@code publishedOn} is free-form display text on
     * the entry, so it can legitimately hold something that is not a date; asserting a wrong
     * {@code datePublished} would be worse than asserting none, because a consumer is told to trust it.
     */
    private static Instant publishedAt(BlogEntry entry) {
        String raw = entry.publishedOn();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * One pass over the published refs → {@code path|lang → id} for O(1) miss-time resolution. Keys the FULL
     * union of the site's locales plus the default (parity with the SPI's default {@code resolve}, which
     * matches {@code pathFor(L)} for any lookup locale), with a locale's OWN localized slug taking precedence
     * over another entry's fallback claim. Two entries claiming one (path, lang) is a same-source collision
     * the kernel cannot see — logged loudly here; the deterministic winner is the explicit/localized claim,
     * else first-listed.
     */
    private Map<String, String> buildIndex(SiteView site) {
        Map<String, String> paths = new HashMap<>();
        Locale siteDefault = site.defaultLocale();
        LinkedHashSet<Locale> lookupLocales = new LinkedHashSet<>();
        lookupLocales.add(siteDefault);
        lookupLocales.addAll(site.locales());
        for (ContentRef ref : enumerate(site)) {
            for (Locale locale : lookupLocales) {
                String key = ref.pathFor(locale) + '|' + locale.getLanguage();
                boolean explicit = ref.localizedPaths().containsKey(locale.getLanguage())
                        || ref.hasLocale(locale, siteDefault);
                String prior = explicit ? paths.put(key, ref.id()) : paths.putIfAbsent(key, ref.id());
                if (prior != null && !prior.equals(ref.id())) {
                    log.warn("blog entries '{}' and '{}' (site '{}') both claim '{}' — '{}' wins; give one a "
                            + "different slug", prior, ref.id(), site.id(), key,
                            explicit ? ref.id() : prior);
                }
            }
        }
        return Map.copyOf(paths);
    }

    /** For the console: the entry's public path in the site's default URL space (list-screen links). */
    public String publicPath(String siteId, BlogEntry entry) {
        return publicPath(siteId, entry, null);
    }

    /**
     * The entry's public path for one language ({@code null} = the default URL space): the localized slug when
     * that language has one, else the default path — the exact semantics {@code toRef}/routing use, so the
     * console's reverse advisory checks the REAL per-language URLs.
     */
    public String publicPath(String siteId, BlogEntry entry, String lang) {
        BlogSettings current = settings(siteId);
        if (lang != null && entry.localizedSlugs().containsKey(lang)) {
            return current.pathFor(entry.localizedSlugs().get(lang));
        }
        return entry.customPath() != null ? entry.customPath()
                : current.pathFor(entry.effectiveSlug());
    }

    /** Test hook: the index currently cached for a site (null = not built). */
    Map<String, String> cachedIndex(String siteId) {
        return index.get(siteId);
    }

    /** Test hook: the refs currently cached for a site (null = not built). */
    List<ContentRef> cachedRefs(String siteId) {
        return refs.get(siteId);
    }
}
