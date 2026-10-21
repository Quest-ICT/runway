package mx.quest.runway.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.web.DraftRenderer;
import mx.quest.runway.web.SiteTemplateResolver;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Kernel pages exposed as a {@link ContentSource} — the <b>no-two-class-system</b> rule realized: every
 * enumeration consumer (the sitemap, llms.txt, URL maps, search) sees pages and plugin content through the
 * <em>same</em> registry and the same {@link ContentRef} noun, so a plugin's content is a first-class peer of
 * the built-ins by construction. Pages also gain what the noun carries and they never had: an
 * {@link ContentRef#updatedAt() updatedAt} (from the page files' mtimes) that becomes the sitemap's
 * {@code <lastmod>}.
 *
 * <p><b>Routing does NOT consult this source.</b> The controller resolves real pages against
 * {@link PageStore} first — the structural "real page wins" precedence — so {@code ContentRouter} skips
 * {@link #KEY} to avoid a redundant second lookup of the same data. This source exists for the
 * <em>enumeration</em> consumers.
 *
 * <p>{@link #body} is empty: a page's body renders through its own leaf template (the {@code bodyTemplate}
 * this source declares), never through a source body payload. Search reads the page's content slot (the
 * {@code id="content"} element) via {@link #indexableText} instead (the capability-gated search integration) —
 * taken from the page <b>as rendered</b> in the requested locale, so text the site's bundles supply through
 * decoupled logic is searchable in every language the site speaks.
 */
@Component
public class PagesContentSource implements ContentSource {

    /** This source's stable key — also the marker {@code ContentRouter} skips for routing. */
    public static final String KEY = "pages";

    private static final String TEMPLATES = "templates";

    /** The page contract's content slot: the one region of a page file that is the page's own. */
    static final String CONTENT_SLOT_ID = "content";

    private static final Logger log = LoggerFactory.getLogger(PagesContentSource.class);

    /** A page or master file name ({@code about}, {@code _master}): no separator, no traversal. */
    private static final java.util.regex.Pattern TEMPLATE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    /** What a visitor cannot read as prose: code, inert markup, no-script fallbacks, and forms with all they hold. */
    private static final String NOT_PROSE = "script, style, template, noscript, form, button, input, select, textarea";

    private final PageStore pages;
    private final StorageLayout layout;
    private final SiteStore sites;
    /** Lazy: the renderer reaches the content catalog, which holds this source. */
    private final ObjectProvider<DraftRenderer> renderer;
    /** Lazy, like {@link #renderer}: the bundles whose fingerprint keys the cache. */
    private final ObjectProvider<SiteAwareMessageSource> messages;
    /** {@code site/page/locale} keys already warned about, so a page that cannot render logs once, not per query. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** Rendered slot texts by {@code site|page|locale}; see {@link #indexableText}. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** The longest a cached text is served without a render, whatever its fingerprint says. */
    static final long MAX_AGE_MILLIS = 10 * 60 * 1000L;
    /** Entries held before the cache is emptied (a crude bound; one entry per page and language). */
    static final int MAX_CACHED = 50_000;

    /** One cached slot text, the fingerprint it was rendered under, and when. */
    private record Cached(String fingerprint, String text, long atMillis) {
    }

    /** Template-file text only (no rendering) — for tests of the enumeration and the slot extraction. */
    public PagesContentSource(PageStore pages, StorageLayout layout) {
        this(pages, layout, null, null, null);
    }

    @Autowired
    public PagesContentSource(PageStore pages, StorageLayout layout, SiteStore sites,
                              ObjectProvider<DraftRenderer> renderer, ObjectProvider<SiteAwareMessageSource> messages) {
        this.pages = pages;
        this.layout = layout;
        this.sites = sites;
        this.renderer = renderer;
        this.messages = messages;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public List<ContentRef> enumerate(SiteView site) {
        List<ContentRef> refs = new ArrayList<>();
        for (Page page : pages.findAll(site.id())) {
            if (page.published()) {
                refs.add(toRef(site.id(), page));
            }
        }
        return refs;
    }

    @Override
    public ContentBody body(SiteView site, String id, Locale locale) {
        return ContentBody.empty(); // a page renders through its own leaf template, not a body payload
    }

    @Override
    public mx.quest.runway.spi.model.Capabilities capabilities() {
        return mx.quest.runway.spi.model.Capabilities.of(CAP_INDEXABLE_TEXT);
    }

    /**
     * The page's own content from its {@code <slug>.html} — search's indexable material (engines strip the
     * markup). Only the <b>content slot</b> is returned (see {@link #contentSlot}): the inner HTML of the
     * element with {@code id="content"}, the page contract's {@code <main id="content">} and the one region a
     * page owns. The rest of the file is the designer's offline copy of the shared chrome (header, navigation,
     * language switcher, footer), which the render replaces with the master's live seams; indexing it put the
     * same navigation words into every page and into every result snippet.
     *
     * <p>Since 1.0.0-rc6 the slot is taken from the page <b>as rendered in {@code locale}</b>, so the strings the
     * render takes from the site's message bundles (decoupled {@code th:text="#{key}"}) are indexed in that
     * language; a page that is unknown, unpublished or fails to render gives its file's slot instead.
     *
     * <p><b>Cached.</b> A rendered text is kept per page and locale under a fingerprint of everything a render of
     * the slot reads that can change without an event: the page's files, its master's files and the site's
     * bundles ({@link SiteAwareMessageSource#bundleState}, which also re-reads bundles edited on disk before the
     * render). An engine's freshness backstop — search-lucene rebuilds every 30 s under query load — therefore
     * costs one {@code stat} per file instead of a render. Page, content, site and plugin-settings events drop the
     * site's entries; {@link #MAX_AGE_MILLIS} bounds what no fingerprint sees (a content widget's own data).
     */
    @Override
    public String indexableText(SiteView site, String id, Locale locale) {
        if (!mx.quest.runway.storage.PathSafety.isSafeId(id)) {
            return ""; // defense in depth — never let an unsafe id reach a filesystem resolve (mirrors PageStore)
        }
        Optional<Page> page = pages.find(site.id(), id).filter(Page::published);
        if (page.isPresent() && renderer != null && sites != null) {
            String key = site.id() + "|" + id + "|" + locale.toLanguageTag();
            String fingerprint = fingerprint(site.id(), page.get());
            Cached hit = cache.get(key);
            long now = System.currentTimeMillis();
            if (hit != null && hit.fingerprint().equals(fingerprint) && now - hit.atMillis() < MAX_AGE_MILLIS) {
                return hit.text();
            }
            String rendered = rendered(site.id(), page.get(), locale);
            String text = rendered != null ? contentSlot(rendered) : fileText(site.id(), id);
            if (cache.size() >= MAX_CACHED) {
                cache.clear(); // a crude bound: the next rebuild renders again, nothing is wrong
            }
            cache.put(key, new Cached(fingerprint, text, now));
            return text;
        }
        return fileText(site.id(), id);
    }

    /** The page file's own slot text (no render): unknown or unpublished pages, and the fallback of a failed render. */
    private String fileText(String siteId, String id) {
        Path file = layout.siteDir(siteId).resolve(TEMPLATES).resolve(id + ".html");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return contentSlot(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return ""; // an unreadable body indexes as title/description only — degrade, don't fail
        }
    }

    /**
     * The published page rendered in {@code locale}, or {@code null} when it cannot be (no renderer, the page is
     * unknown or unpublished, or the render failed). A failed render falls back to the template file's text —
     * the literal text is still searchable — and is logged once per page and locale.
     */
    private String rendered(String siteId, Page page, Locale locale) {
        DraftRenderer draft = renderer.getIfAvailable();
        Optional<Site> site = sites.find(siteId);
        if (draft == null || site.isEmpty()) {
            return null;
        }
        String id = page.slug();
        try {
            return draft.renderForIndex(site.get(), page, locale);
        } catch (RuntimeException ex) {
            if (warned.add(siteId + "/" + id + "/" + locale.toLanguageTag())) {
                log.warn("search: page '{}' of site '{}' could not be rendered in {}; indexing its template text "
                        + "instead (strings from the site's bundles are missing from the index)",
                        id, siteId, locale.toLanguageTag(), ex);
            }
            return null;
        }
    }

    /**
     * What a cached text is valid for: the mtimes of the page's and its master's files, and the site's bundle
     * fingerprint (taken after the bundles are re-checked, so a render that follows reads the same bundles).
     */
    private String fingerprint(String siteId, Page page) {
        StringBuilder b = new StringBuilder(96);
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES);
        for (String name : new String[] {page.slug(), page.template()}) {
            if (name == null || !TEMPLATE_NAME.matcher(name).matches() || name.contains("..")) {
                continue;
            }
            for (String suffix : new String[] {".meta.yml", ".html", ".th.xml"}) {
                try {
                    Path file = templates.resolve(name + suffix);
                    b.append(Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L).append(',');
                } catch (IOException ex) {
                    b.append("?,");
                }
            }
        }
        SiteAwareMessageSource bundles = messages == null ? null : messages.getIfAvailable();
        if (bundles != null) {
            try {
                b.append(bundles.bundleState(siteId).fingerprint());
            } catch (RuntimeException ex) {
                b.append("?"); // no fingerprint: an unchanged "?" still lets MAX_AGE_MILLIS refresh the entry
            }
        }
        return b.toString();
    }

    /**
     * Drops every cached text of a site: its pages, content, settings or the site itself changed. At
     * {@code DERIVED}: after the stores and bundles it reads have evicted, before plugins hear of the change and
     * rebuild their indexes.
     */
    private void evict(String siteId) {
        String prefix = siteId + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.DERIVED)
    public void onPageChanged(PageChangedEvent event) {
        evict(event.siteId());
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.DERIVED)
    public void onContentChanged(ContentChangedEvent event) {
        evict(event.siteId());
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.DERIVED)
    public void onSiteChanged(SiteChangedEvent event) {
        evict(event.siteId());
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.DERIVED)
    public void onSitePluginConfigChanged(SitePluginConfigChangedEvent event) {
        if (event.siteId() != null) {
            evict(event.siteId());
        }
    }

    /**
     * The indexable part of a page (rendered, or its template file): the inner HTML of the element with
     * {@code id="content"}; without one (a standalone page that skipped the slot), the {@code <body>}'s inner
     * HTML; and when the body is empty too, the text as it is. Code ({@code <script>}, {@code <style>},
     * {@code <template>}), {@code <noscript>} fallbacks, forms (with everything inside them) and stray buttons and
     * fields are dropped: they are not prose a visitor searches for.
     *
     * @param html the page's HTML (never {@code null})
     * @return the slot's inner HTML, never {@code null}
     */
    static String contentSlot(String html) {
        Document doc = Jsoup.parse(html);
        Element slot = doc.getElementById(CONTENT_SLOT_ID);
        Element scope = slot != null ? slot : doc.body();
        if (scope.childNodeSize() == 0) {
            return slot != null ? "" : html; // an empty slot has nothing to index; a body-less file is kept whole
        }
        scope.select(NOT_PROSE).remove();
        return scope.html();
    }

    /** An index probe is unnecessary: {@link PageStore#findByPath} is the same lookup routing itself uses. */
    @Override
    public Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
        return pages.findByPath(site.id(), path, locale)
                .filter(Page::published)
                .map(page -> toRef(site.id(), page));
    }

    /**
     * Projects a page onto the content noun — the ref's {@code path}/{@code localizedPaths} are the page's own
     * (so {@link ContentRef#pathFor} reproduces {@link Page#pathFor} exactly), plus the mtime-derived
     * {@code updatedAt} the sitemap turns into {@code <lastmod>}.
     */
    private ContentRef toRef(String siteId, Page page) {
        return new ContentRef(page.slug(), page.path(), page.localizedPaths(), page.locales(), page.title(),
                page.description(), page.activeNav(), SiteTemplateResolver.PREFIX + siteId + "/" + page.slug(),
                page.published(), updatedAt(siteId, page), page.noindex(), page.canonical(), page.cacheControl(),
                page.params(), page.author(), page.publishedAt());
    }

    /** The newest mtime of the page's files ({@code .meta.yml}, {@code .html}, {@code .th.xml}), or null. */
    /**
     * The page's {@code updatedAt} — the newest mtime across its {@code .meta.yml}, {@code .html} and
     * {@code .th.xml} — which the sitemap turns into {@code <lastmod>}.
     *
     * <h2>Why mtime, and the false positive it accepts on purpose</h2>
     * mtime answers "was this file touched", not "did what we serve change". So editing a template in a way
     * that leaves the rendered bytes identical still moves {@code lastmod}, and the site tells search engines
     * something changed when nothing they can see did. Observed on a live deployment: a template edit across two
     * demo sites moved {@code lastmod} on every page of both, and on one of them the delivered output was
     * byte-identical.
     *
     * <p>The obvious alternative — persist an explicit {@code updatedAt} written by the page editor and
     * prefer it over mtime — trades that false positive for a <b>false negative</b>, which is worse. A
     * template edited on disk (which is how a designer works, and how both of those sites are maintained)
     * would then change what visitors receive while {@code lastmod} stayed put, and engines would not
     * re-crawl content that genuinely changed. Being told about a change that did not happen costs a wasted
     * crawl; not being told about one that did costs the index being wrong.
     *
     * <p>Comparing rendered output instead would mean rendering every page of every site on every sitemap
     * request, which is precisely the cost this metadata-only projection exists to avoid.
     *
     * <p>So the conservatism is deliberate: {@code lastmod} over-reports and never under-reports. An operator
     * doing a bulk cosmetic edit should expect a crawl afterwards.
     */
    private Instant updatedAt(String siteId, Page page) {
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES);
        long newest = 0L;
        for (String suffix : new String[] {".meta.yml", ".html", ".th.xml"}) {
            Path file = templates.resolve(page.slug() + suffix);
            try {
                if (Files.isRegularFile(file)) {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime > newest) {
                        newest = mtime;
                    }
                }
            } catch (IOException ignored) {
                // an unreadable file contributes no mtime; the ref still enumerates
            }
        }
        return newest == 0L ? null : Instant.ofEpochMilli(newest);
    }
}
